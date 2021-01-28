package tia.test.spark.rest;

import com.alibaba.csp.sentinel.adapter.okhttp.SentinelOkHttpConfig;
import com.alibaba.csp.sentinel.adapter.okhttp.SentinelOkHttpInterceptor;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import io.micrometer.core.instrument.binder.okhttp3.OkHttpMetricsEventListener;
import okhttp3.CacheControl;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Connection;
import okhttp3.ConnectionPool;
import okhttp3.HttpUrl;
import okhttp3.JavaNetCookieJar;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.logging.HttpLoggingInterceptor;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tia.test.spark.common.Meter;
import tia.test.spark.common.Throttler;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.CookieManager;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Phaser;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public class HintsRest implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(HintsRest.class);

    private static final String SPARK_HINT_TEST_HOST = "hint-devel.spark-interfax.ru/search";
    private static final String SPARK_HINT_PROD_HOST = "hint.spark-interfax.ru/search";
    private final OkHttpClient okHttpClient;
    private final Throttler throttler;
    Request.Builder requestBuilder;
    private final HttpUrl.Builder urlBuilder;

    public HintsRest(Meter meter, Throttler throttler, boolean ssl, boolean test) {

        this.throttler = throttler;

        HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor();
        loggingInterceptor.level(HttpLoggingInterceptor.Level.BASIC);

        SentinelOkHttpConfig sentinelConfig = new SentinelOkHttpConfig(
                (Request request, Connection connection) -> request.url().host(),
                //new DefaultOkHttpFallback()
                // Callback.onFailure was called before
                (Request request, Connection connection, BlockException e) -> new Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(503)
                        .message("Service Unavailable")
                        .body(ResponseBody.create("{}", MediaType.get("application/json; charset=utf-8")))
                        .build()
        );

        okHttpClient = new OkHttpClient.Builder()
                .eventListener(OkHttpMetricsEventListener.builder(meter.getRegistry(), "okhttp")
                        .uriMapper(req -> req.url().encodedPath())
                        //.tags(Tags.of("module", "spark"))
                        .build()
                )
                .connectionPool(new ConnectionPool(10, 15, TimeUnit.MINUTES))
                .cookieJar(new JavaNetCookieJar(new CookieManager()))
                .hostnameVerifier(
                        (hostname, session) -> true // TODO implement for prod
                )
                //.addNetworkInterceptor(loggingInterceptor)
                //.addInterceptor(loggingInterceptor)
                .addInterceptor(new SentinelOkHttpInterceptor(sentinelConfig))
                .build();

        String protocol;
        if (ssl) {
            protocol = "https";

        } else {
            protocol = "http";
        }
        String host;
        if (test) {
            host = SPARK_HINT_TEST_HOST;
        } else {
            host = SPARK_HINT_PROD_HOST;
        }

        urlBuilder = HttpUrl.parse(protocol + "://" + host)
                .newBuilder()
                .addQueryParameter("count", "50");

        requestBuilder = new Request.Builder()
                //.url( protocol + "://" + host + "?query=Интер&regions=1&count=45")
                //.url( protocol + "://" + host + "?query=Интер&count=45")
                .cacheControl(CacheControl.FORCE_NETWORK) // TODO Do not use in prod
                .get()
        ;
        logger.info("Address: {}", urlBuilder.build().redact());
        initDegradeRule(urlBuilder.build());
        disableSentinelMetricLogger();
    }

    public void testSparkRest(int count) {

        Phaser phaser = new Phaser(1);
        /*RequestBody body = RequestBody.create(
                "{\"query\":\"интер\",\"count\":3,\"objectTypes\":0,\"regions\":[]}",
                MediaType.get("application/json; charset=UTF-8"));*/

        for (int i = 0; i < count; i++) {
            phaser.register();

            urlBuilder.removeAllQueryParameters("query");
            String randomOrgName = newRandomOrgName();
            //String randomOrgName = "Петров Андрей Борисович";
            HttpUrl url = urlBuilder.addQueryParameter("query", randomOrgName).build();
            Request request = requestBuilder.url(url).build();

            throttler.pause();
            okHttpClient.newCall(request).enqueue(
                    new Callback() {
                        @Override
                        public void onFailure(@NotNull Call call, @NotNull IOException e) {
                            logger.debug("Error: {}", e.getMessage());
                            phaser.arriveAndDeregister();
                        }

                        @Override
                        public void onResponse(@NotNull Call call, @NotNull Response response) {
                            if (logger.isDebugEnabled()) {
                                logger.debug("Status {}", response.code());
                                try {
                                    String s = response.body().string();
                                    if (response.code() == 200) {
                                        logger.debug(s.length() >= 40 ? s.substring(0, 40) + "..." : s);
                                    } else {
                                        logger.debug(s);
                                    }
                                } catch (IOException e) {
                                    logger.error("Response is not readable", e);
                                }
                            }
                            phaser.arriveAndDeregister();
                            response.close();
                        }
                    }
            );
        }
        phaser.arriveAndAwaitAdvance();
    }

    private static String newRandomOrgName() {
        int leftLimit = 'А'; // russian
        int rightLimit = 'я'; // russian
        int delta = rightLimit - leftLimit + 1;
        Random random = ThreadLocalRandom.current();
        int len = 3 + random.nextInt(7);
        StringBuilder buffer = new StringBuilder(len);

        for (int i = 0; i < len; i++) {
            int randomLimitedInt = leftLimit + random.nextInt(delta);
            buffer.append((char) randomLimitedInt);
        }
        return buffer.toString();
    }

    @Override
    public void close() throws Exception {
        //OkHttp is closing long time on shutdown without this if Call#enqueue was used
        okHttpClient.dispatcher().executorService().shutdown(); // Must
        okHttpClient.dispatcher().executorService().awaitTermination(2, TimeUnit.SECONDS);
        okHttpClient.connectionPool().evictAll(); // For safety https://github.com/square/okhttp/issues/2575
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
     *
     * @param url
     */
    private void initDegradeRule(HttpUrl url) {

        String resourceName = SentinelOkHttpConfig.DEFAULT_RESOURCE_PREFIX + url.host();
        DegradeRule exceptionRatioRule = new DegradeRule(resourceName)
                .setCount(0.1) // errCount/totalCount for DEGRADE_GRADE_EXCEPTION_RATIO
                .setTimeWindow(60)
                .setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO)
                //.setStatIntervalMs(10)
                ;
        DegradeRuleManager.loadRules(Arrays.asList(exceptionRatioRule));
    }

    /**
     * Отключение записи метрик Sentinel в файлы. Легального способа отключить найти не удалось.
     */
    private void disableSentinelMetricLogger() {
        try {
            Field f = FlowRuleManager.class.getDeclaredField("SCHEDULER");
            f.setAccessible(true);
            ExecutorService service = (ExecutorService) f.get(null);
            service.shutdown();
            f.setAccessible(false);

        } catch (Exception e) {

        }
    }
}
