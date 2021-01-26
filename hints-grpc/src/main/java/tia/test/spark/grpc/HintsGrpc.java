package tia.test.spark.grpc;

import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.client.metric.MetricCollectingClientInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tia.test.spark.common.Meter;
import tia.test.spark.common.Throttler;
import tia.test.spark.hint.proto.ExtHint;
import tia.test.spark.hint.proto.ExtHintServiceGrpc;
import tia.test.spark.sentinel.SentinelGrpcClientInterceptor2;

import java.util.Arrays;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;

public class HintsGrpc implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(HintsGrpc.class);

    private static final int OBJ_TYPE_COMPANY = 0b0001;
    private static final int OBJ_TYPE_SUBSIDIARY = 0b0010;
    private static final int OBJ_TYPE_ENTREPRENEUR = 0b0100;

    private static final String SPARK_HINT_TEST_HOST = "hint-devel.spark-interfax.ru";
    private static final int SPARK_HINT_TEST_PORT = 50099;

    private static final String SPARK_HINT_PROD_HOST = "hint.spark-interfax.ru";
    private static final int SPARK_HINT_PROD_PORT = 50093;
    //private static final int SPARK_HINT_PROD_PORT = 50099; // Работает без TLS, но этот порт для разработки

    private final Meter meter;
    private final Throttler throttler;
    private ManagedChannel channel;
    private ExtHintServiceGrpc.ExtHintServiceBlockingStub blockingStub;
    private ExtHintServiceGrpc.ExtHintServiceStub asyncStub;

    public HintsGrpc(Meter meter, Throttler throttler, boolean ssl, boolean test) {
        this.meter = meter;
        this.throttler = throttler;

        ManagedChannelBuilder<?> channelBuilder;
        if (test) {
            channelBuilder = ManagedChannelBuilder.forAddress(SPARK_HINT_TEST_HOST, SPARK_HINT_TEST_PORT);
        } else {
            channelBuilder = ManagedChannelBuilder.forAddress(SPARK_HINT_PROD_HOST, SPARK_HINT_PROD_PORT);
        }
        if (!ssl) {
            channelBuilder.usePlaintext();
        }
        channel = channelBuilder
                .intercept(new SentinelGrpcClientInterceptor2())
                //.intercept(new MetricCollectingClientInterceptor(meter.getRegistry()))
                .build();
        blockingStub = ExtHintServiceGrpc.newBlockingStub(channel);
        asyncStub = ExtHintServiceGrpc.newStub(channel);

        logger.info("Address: {}", channel.authority());

        initDegradeRule();
    }

    @Override
    public void close() throws Exception {
        // ManagedChannels use resources like threads and TCP connections. To prevent leaking these
        // resources the channel should be shut down when it will no longer be used. If it may be used
        // again leave it running.
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }

    public void testSparkGrpc(int count) {
        Phaser phaser = new Phaser(1);

        // Recover after meter.clear(). Otherwise it's better to inject interceptor by ManagedChannelBuilder.
        ExtHintServiceGrpc.ExtHintServiceStub localAsyncStub = this.asyncStub.withInterceptors(
                new MetricCollectingClientInterceptor(meter.getRegistry())
        );

        ExtHint.HintRequest payload = ExtHint.HintRequest.newBuilder()
                .setQuery("Интер")
                //.setObjectTypes(OBJ_TYPE_COMPANY | OBJ_TYPE_ENTREPRENEUR | OBJ_TYPE_SUBSIDIARY)
                .setCount(45) // 1-50
                .build();
        try {

            /*ExtHint.HintResponse hintResponse = blockingStub.autocomplete(payload);
            logger.debug("OK. Found: {}", hintResponse.getValuesList().size());*/

            for (int i = 0; i < count; i++) {
                phaser.register();
                throttler.pause();
                localAsyncStub.autocomplete(payload, new StreamObserver<ExtHint.HintResponse>() {
                    @Override
                    public void onNext(ExtHint.HintResponse hintResponse) {
                        logger.debug("OK. Found: {}", hintResponse.getValuesList().size());
                                /*for (ExtHint.SearchResult result : hintResponse.getValuesList()) {
                                    logger.debug("OK. {}", result.getFullName());
                                }*/
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        if (!BlockException.isBlockException(throwable)){
                            logger.debug(throwable.getMessage());
                        }
                        phaser.arriveAndDeregister();
                    }

                    @Override
                    public void onCompleted() {
                        phaser.arriveAndDeregister();
                    }
                });
            }

            phaser.arriveAndAwaitAdvance();

        } catch (Exception e) {
            logger.error("SparkGrpc error", e);
        }
    }

    /**
     * <table cellpadding=3>
     * <th>Field              <th> Description	<th> Default value
     * <tr>resource           <td> resource name <td>
     * <tr>count              <td> threshold <td>
     * <tr>grade              <td> circuit breaking strategy (slow request ratio/error ratio/error count)	<td>slow request ratio
     * <tr>timeWindow         <td> circuit breaker recovery timeout (in second)
     * <tr>minRequestAmount   <td> the minimum number of calls that are required (per sliding window period) before the circuit breaker can calculate the ratio or total amount (since 1.7.0)	<td>5
     * <tr>statIntervalMs     <td> sliding window period (since 1.8.0)	<td> 1000
     * <tr>slowRatioThreshold <td> threshold of the slow ratio, only available for slow ratio strategy (since 1.8.0)
     * </table>
     *  @see <a href="https://github.com/alibaba/Sentinel/wiki/How-to-Use#circuit-breaking-rules-degraderule">Circuit breaking rules</a>*
     */
    private void initDegradeRule() {
        // Must match gRPC method auto generated name
        //String resourceName = ExtHintServiceGrpc.getAutocompleteMethod().getFullMethodName();
        String resourceName = ExtHintServiceGrpc.getAutocompleteMethod().getServiceName();
        DegradeRule exceptionRatioRule = new DegradeRule(resourceName)
                .setCount(0.1) // errCount/totalCount for DEGRADE_GRADE_EXCEPTION_RATIO
                .setTimeWindow(60)
                .setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO)
                //.setStatIntervalMs(10)
                ;
        DegradeRuleManager.loadRules(Arrays.asList(exceptionRatioRule));
    }
}
