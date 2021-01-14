package tia.test.spark.hint;

import io.micrometer.core.instrument.binder.okhttp3.OkHttpMetricsEventListener;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.ConnectionPool;
import okhttp3.JavaNetCookieJar;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.logging.HttpLoggingInterceptor;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.CookieManager;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;

public class HintsRest implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(HintsRest.class);

    private static final String SPARK_HINT_TEST_HOST = "hint-devel.spark-interfax.ru/search";
    private static final String SPARK_HINT_PROD_HOST = "hint.spark-interfax.ru/search";

    private final OkHttpClient okHttpClient;
    private final Throttler throttler;
    Request request;

    public HintsRest(Meter meter, Throttler throttler, boolean ssl, boolean test) {

        this.throttler = throttler;

        HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor();
        loggingInterceptor.level(HttpLoggingInterceptor.Level.BASIC);

        okHttpClient = new OkHttpClient.Builder()
                .eventListener(OkHttpMetricsEventListener.builder(meter.getRegistry(), "okhttp")
                        .uriMapper(req -> req.url().encodedPath())
                        //.tags(Tags.of("module", "spark"))
                        .build()
                )
                .connectionPool(new ConnectionPool(5, 15, TimeUnit.SECONDS))
                .cookieJar(new JavaNetCookieJar(new CookieManager()))
                .hostnameVerifier(
                        (hostname, session) -> true
                )
                //.addNetworkInterceptor(loggingInterceptor)
                //.addInterceptor(loggingInterceptor)
                .build();

        String protocol;
        if (ssl){
            protocol = "https:";

        } else {
            protocol = "http:";
        }
        String host;
        if (test){
            host = SPARK_HINT_TEST_HOST;
        } else {
            host = SPARK_HINT_PROD_HOST;
        }

        request = new Request.Builder()
                .url( protocol + "//" + host + "?query=Интер&regions=1&count=45")
                .get()
                .build();
        logger.info("Address: {}", request.url().redact());
    }

    public void testSparkRest(int count) {

        Phaser phaser = new Phaser(1);
        /*RequestBody body = RequestBody.create(
                "{\"query\":\"интер\",\"count\":3,\"objectTypes\":0,\"regions\":[]}",
                MediaType.get("application/json; charset=UTF-8"));*/

        for (int i = 0; i < count; i++) {
            phaser.register();
            throttler.pause();
            okHttpClient.newCall(request).enqueue(
                    new Callback() {
                        @Override
                        public void onFailure(@NotNull Call call, @NotNull IOException e) {
                            logger.debug("Error {}", e.getMessage());
                            phaser.arriveAndDeregister();
                        }

                        @Override
                        public void onResponse(@NotNull Call call, @NotNull Response response) {
                            if (logger.isDebugEnabled()) {
                                logger.debug("Status {}", response.code());
                                try {
                                    if (response.code() == 200){
                                        logger.debug(response.body().string().substring(0, 40) + "...");
                                    } else {
                                        logger.debug(response.body().string());
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

    @Override
    public void close() throws Exception {
        //OkHttp is closing long time on shutdown without this if Call#enqueue was used
        okHttpClient.dispatcher().executorService().shutdown(); // Must
        okHttpClient.dispatcher().executorService().awaitTermination(2, TimeUnit.SECONDS);
        okHttpClient.connectionPool().evictAll(); // For safety https://github.com/square/okhttp/issues/2575
    }
}
