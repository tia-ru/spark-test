package tia.test.spark.hint;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.client.metric.MetricCollectingClientInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tia.test.spark.hint.proto.ExtHint;
import tia.test.spark.hint.proto.ExtHintServiceGrpc;

import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;

public class HintsGrpc implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(HintsGrpc.class);

    private static final int OBJ_TYPE_COMPANY = 0b0001;
    private static final int OBJ_TYPE_SUBSIDIARY = 0b0010;
    private static final int OBJ_TYPE_ENTREPRENEUR = 0b0100;
    public static final String SPARK_HINT_TEST_HOST = "hint-devel.spark-interfax.ru";
    public static final int SPARK_HINT_TEST_PORT = 50099;
    public static final String SPARK_HINT_PROD_HOST = "hint-devel.spark-interfax.ru";
    public static final int SPARK_HINT_PROD_PORT = 50099;

    private final Meter meter;
    private final boolean ssl;
    private final boolean test;
    private final Throttler throttler;
    private  ManagedChannel channel;
    private  ExtHintServiceGrpc.ExtHintServiceBlockingStub blockingStub;
    private  ExtHintServiceGrpc.ExtHintServiceStub asyncStub;

    public HintsGrpc(Meter meter, Throttler throttler, boolean ssl, boolean test) {
        this.meter = meter;
        this.ssl = ssl;
        this.test = test;
        ManagedChannelBuilder<?> channelBuilder;
        if (test) {
            channelBuilder = ManagedChannelBuilder.forAddress(SPARK_HINT_TEST_HOST, SPARK_HINT_TEST_PORT);
        } else {
            channelBuilder = ManagedChannelBuilder.forAddress(SPARK_HINT_PROD_HOST, SPARK_HINT_PROD_PORT);
        }
        if (!ssl){
            channelBuilder.usePlaintext();
        }
        channel = channelBuilder
                //.intercept(new MetricCollectingClientInterceptor(meter.getRegistry()))
                .build();
        blockingStub = ExtHintServiceGrpc.newBlockingStub(channel);
        asyncStub = ExtHintServiceGrpc.newStub(channel);
        this.throttler = throttler;
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
                            ExtHint.HintResponse response = hintResponse;
                            logger.debug("OK. {}", result.getFullName());
                        }*/
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        logger.debug("Error {}", throwable.getMessage());
                        phaser.arriveAndDeregister();
                    }

                    @Override
                    public void onCompleted() {
                        phaser.arriveAndDeregister();
                    }
                });
            }

            phaser.arriveAndAwaitAdvance();

        } catch (Exception e){
            logger.error("SparkGrpc error", e);
        }
    }
}
