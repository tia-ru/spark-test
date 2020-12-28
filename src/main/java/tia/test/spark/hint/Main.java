/*
 * Copyright 2015 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package tia.test.spark.hint;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.binder.okhttp3.OkHttpMetricsEventListener;
import net.devh.boot.grpc.client.metric.MetricCollectingClientInterceptor;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.ConnectionPool;
import okhttp3.JavaNetCookieJar;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.logging.HttpLoggingInterceptor;
import org.apache.cxf.message.Message;
import org.apache.cxf.metrics.MetricsFeature;
import org.apache.cxf.metrics.MetricsProvider;
import org.apache.cxf.metrics.micrometer.MicrometerMetricsProperties;
import org.apache.cxf.metrics.micrometer.MicrometerMetricsProvider;
import org.apache.cxf.metrics.micrometer.provider.DefaultExceptionClassProvider;
import org.apache.cxf.metrics.micrometer.provider.DefaultTimedAnnotationProvider;
import org.apache.cxf.metrics.micrometer.provider.StandardTags;
import org.apache.cxf.metrics.micrometer.provider.StandardTagsProvider;
import org.apache.cxf.metrics.micrometer.provider.TagsCustomizer;
import org.apache.cxf.metrics.micrometer.provider.TagsProvider;
import org.apache.cxf.metrics.micrometer.provider.jaxws.JaxwsFaultCodeProvider;
import org.apache.cxf.metrics.micrometer.provider.jaxws.JaxwsFaultCodeTagsCustomizer;
import org.apache.cxf.metrics.micrometer.provider.jaxws.JaxwsOperationTagsCustomizer;
import org.apache.cxf.metrics.micrometer.provider.jaxws.JaxwsTags;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;
import ru.interfax.ifax.GetCompanyListByNameResponse;
import ru.interfax.ifax.IFaxWebService;
import ru.interfax.ifax.IFaxWebServiceSoap;
import tia.test.spark.hint.proto.ExtHint;
import tia.test.spark.hint.proto.ExtHintServiceGrpc;

import javax.xml.ws.BindingProvider;
import javax.xml.ws.Holder;
import java.io.IOException;
import java.net.CookieManager;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogManager;

/**
 * http-proxy 169.254.0.183:8080
 * Для определения прокси исп. сист. св-ва
 *  -Dhttp.proxyHost=169.254.0.183 -Dhttp.proxyPort=8080 -Dhttps.proxyHost=169.254.0.183 -Dhttps.proxyPort=8080
 *
 * Актуальные ссылки и документацию:
 * Тестовый - http://sparkgatetest.interfax.ru/IfaxWebService/ifaxwebservice.asmx
 * Боевой - http://webservicefarm.interfax.ru/IfaxWebService/ifaxwebservice.asmx
 * Боевой с поддержкой SSL - https://api.spark-interfax.ru/IfaxWebService/
 * Техническая документация доступна по адресу - https://yadi.sk/d/WWV5gArVguQWgg?w=1
 * Стенд сервиса подсказок - http://hint-demo.spark-interfax.ru/
 *
 * Адрес тестового контура сервиса подсказок (gRPC): http://hint-devel.spark-interfax.ru:50099
 * Также мы разработали вариант REST-сервиса: http://hint-devel.spark-interfax.ru/search
 * Для доступа к сервису подсказок авторизация не требуется.
 * Запросы должны осуществляется с тех IP-адресов, которые привязаны к боевому логину API СПАРК клиента.
 * Документация к сервису подсказок: http://hint-demo.spark-interfax.ru/manual/
 */
public class Main implements AutoCloseable {
    //private static final Logger logger = Logger.getLogger(Main.class.getName());
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    private static final int OBJ_TYPE_COMPANY = 0b0001;
    private static final int OBJ_TYPE_SUBSIDIARY = 0b0010;
    private static final int OBJ_TYPE_ENTREPRENEUR = 0b0100;
    private static final int DEFAULT_COUNT = 3;
    private static final int THROTTLE_MS = 10;//1000 ms/10ms = 100 requests/sec

    private Phaser phaser = new Phaser(1);
    private OkHttpClient okHttpClient;
    private String login;
    private String pwd;
    private final Meter meter;

    public static void main(String[] args) throws Exception {

        if (args.length < 2) {
            System.out.println("Usage:");
            System.out.println("java -Dhttp.proxyHost=<ip> -Dhttp.proxyPort=<port> -Dhttps.proxyHost=<ip> -Dhttps.proxyPort=<port> -jar spark-test-1.0.jar <login> <password> [<requests count>]");
            return;
        }
        String login = args[0];
        String pwd = args[1];

        int reqCount = DEFAULT_COUNT;
        if (args.length >= 3) {
            reqCount = Integer.parseInt(args[2]);
        }
        Meter meter = new Meter();
        try(Main m = new Main(login, pwd, meter)) {

            /*System.out.println("===================== YANDEX HTTP ==============================================");
            m.testYandex(3);*/

            System.out.println("===================== SPARK HTTP REST ==========================================");
            //Warm up
            m.testSparkRest(reqCount / 5);
            meter.clear();
            m.testSparkRest(reqCount);
            meter.reportAndClear();

            System.out.println("===================== SPARK gRPC ===============================================");
            m.testSparkGrpc(reqCount / 5);
            meter.clear();
            m.testSparkGrpc(reqCount);
            meter.reportAndClear();

            System.out.println("===================== SPARK SOAP ===============================================");
            m.testSparkSoap(reqCount / 5);
            meter.clear();
            m.testSparkSoap(reqCount);
            meter.reportAndClear();
        }

    }

    public Main(String login, String pwd, Meter meter) {
        this.login = login;
        this.pwd = pwd;
        this.meter = meter;

        java.util.logging.Logger rootLogger = LogManager.getLogManager().getLogger("");
        Handler[] handlers = rootLogger.getHandlers();

        for (Handler handler : handlers) {
            rootLogger.removeHandler(handler);
        }
        SLF4JBridgeHandler.install();

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
    }

    public void close() throws InterruptedException {
        //OkHttp is closing long time on shutdown without this if Call#enqueue was used
        okHttpClient.dispatcher().executorService().shutdown(); // Must
        okHttpClient.dispatcher().executorService().awaitTermination(2, TimeUnit.SECONDS);
        okHttpClient.connectionPool().evictAll(); // For safety https://github.com/square/okhttp/issues/2575
        meter.close();
    }

    private void testYandex(int count) {
        Request request = new Request.Builder()
                .url("https://ya.ru")
                .get()
                .build();
        for (int i = 0; i < count; i++) {
            phaser.register();
            okHttpClient.newCall(request).enqueue(
                    new Callback() {
                        @Override
                        public void onFailure(@NotNull Call call, @NotNull IOException e) {
                            logger.debug("Error {}", e.getMessage());
                            phaser.arriveAndDeregister();
                        }

                        @Override
                        public void onResponse(@NotNull Call call, @NotNull Response response) {
                            logger.debug("Status {}", response.code());
                            phaser.arriveAndDeregister();
                            response.close();
                        }
                    }
            );

        }
        phaser.arriveAndAwaitAdvance();
        meter.reportAndClear();
    }

    private void testSparkRest(int count) {

        /*RequestBody body = RequestBody.create(
                "{\"query\":\"интер\",\"count\":3,\"objectTypes\":0,\"regions\":[]}",
                MediaType.get("application/json; charset=UTF-8"));*/
        Request request = new Request.Builder()
                //.url("https://hint-devel.spark-interfax.ru/search?query=Иванов&count=25&object_types=1&regions=1,2,3,4,5,6,7,8,9,40,50&turnonhl=true")
                .url("http://hint-devel.spark-interfax.ru/search?query=Интер&regions=1&count=45")
                .get()
                .build();
        Throttler throttler = new Throttler(THROTTLE_MS);
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
                            logger.debug("Status {}", response.code());
                            phaser.arriveAndDeregister();
                            response.close();
                        }
                    }
            );

        }
        phaser.arriveAndAwaitAdvance();
    }

    private void testSparkGrpc(int count) throws InterruptedException {

        ManagedChannel channel = ManagedChannelBuilder.forAddress("hint-devel.spark-interfax.ru", 50099)
                .intercept(new MetricCollectingClientInterceptor(meter.getRegistry()))
                .usePlaintext() //SSL сервис не поддерживается?
                .build();

        ExtHint.HintRequest payload = ExtHint.HintRequest.newBuilder()
                .setQuery("Интер")
                //.setObjectTypes(OBJ_TYPE_COMPANY | OBJ_TYPE_ENTREPRENEUR | OBJ_TYPE_SUBSIDIARY)
                .setCount(45) // 1-50
                .build();
        try {
            ExtHintServiceGrpc.ExtHintServiceBlockingStub stub = ExtHintServiceGrpc.newBlockingStub(channel);
            ExtHint.HintResponse hintResponse = stub.autocomplete(payload);
            logger.debug("OK. Found: {}", hintResponse.getValuesList().size());

            ExtHintServiceGrpc.ExtHintServiceStub serviceStub = ExtHintServiceGrpc.newStub(channel);
            Throttler throttler = new Throttler(THROTTLE_MS);
            for (int i = 0; i < count; i++) {
                phaser.register();
                throttler.pause();
                serviceStub.autocomplete(payload, new StreamObserver<ExtHint.HintResponse>() {
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
        } finally {
            // ManagedChannels use resources like threads and TCP connections. To prevent leaking these
            // resources the channel should be shut down when it will no longer be used. If it may be used
            // again leave it running.
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private void testSparkSoap(int count) {
        final JaxwsTags jaxwsTags = new JaxwsTags();
        final TagsCustomizer operationsCustomizer = new JaxwsOperationTagsCustomizer(jaxwsTags);
        final TagsCustomizer faultsCustomizer = new JaxwsFaultCodeTagsCustomizer(jaxwsTags, new JaxwsFaultCodeProvider());

        final TagsProvider tagsProvider = new StandardTagsProvider(new DefaultExceptionClassProvider(), new StandardTags());
        final MetricsProvider metricsProvider = new MicrometerMetricsProvider(
                meter.getRegistry(),
                tagsProvider,
                Arrays.asList(operationsCustomizer, faultsCustomizer),
                new DefaultTimedAnnotationProvider(),
                new MicrometerMetricsProperties());

        /*MicrometerMetricsProvider metricsProvider = new MicrometerMetricsProvider(
                Meter.REGISTRY,
                new StandardTagsProvider(new DefaultExceptionClassProvider(), new StandardTags()),
                Collections.emptyList(),
                new DefaultTimedAnnotationProvider(),
                new MicrometerMetricsProperties()
        );*/
        MetricsFeature metricsFeature = new MetricsFeature(metricsProvider);

        IFaxWebService iFaxWebService = new IFaxWebService();
        IFaxWebServiceSoap spark = iFaxWebService.getIFaxWebServiceSoap(metricsFeature);

        Map<String, Object> requestContext = ((BindingProvider) spark).getRequestContext();
        requestContext.put(Message.MAINTAIN_SESSION, Boolean.TRUE);

        /*
        Client client = ClientProxy.getClient(spark);
        HTTPConduit http = (HTTPConduit) client.getConduit();
        HTTPClientPolicy httpClientPolicy = new HTTPClientPolicy();

        httpClientPolicy.setConnectionTimeout(36000);
        httpClientPolicy.setAllowChunking(false);
        httpClientPolicy.setReceiveTimeout(32000);

        http.setClient(httpClientPolicy);*/

        /*WebClient.getConfig(proxy).getRequestContext().put(
                org.apache.cxf.message.Message.MAINTAIN_SESSION, Boolean.TRUE);*/

        String result = spark.authmethod(login, pwd);
        if (!"True".equals(result)){
            logger.error("AuthN failed. Result: {}", result);
            return;
        }
        Throttler throttler = new Throttler(THROTTLE_MS);
        for (int i = 0; i < count; i++) {
            Holder<String> resultHolder = new Holder<>();
            Holder<String> xmlDataHolder = new Holder<>();
            phaser.register();
            throttler.pause();
            spark.getCompanyListByNameAsync("Интер", "1", "0", "1",
                    resultHolder, xmlDataHolder, response -> {
                        try {
                            GetCompanyListByNameResponse r = response.get();
                            logger.debug("Result: {}", r.getGetCompanyListByNameResult());
                            logger.debug("xml: {}", r.getXmlData());
                        } catch (InterruptedException | ExecutionException e) {
                            logger.error("SparkSoap error", e);
                        }
                        phaser.arriveAndDeregister();
                    });
        }

        phaser.arriveAndAwaitAdvance();
        spark.end();
    }
}
