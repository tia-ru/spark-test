package tia.test.spark.hint;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.interfax.ifax.GetCompanyListByNameResponse;
import ru.interfax.ifax.IFaxWebService;
import ru.interfax.ifax.IFaxWebServiceSoap;

import javax.xml.ws.BindingProvider;
import javax.xml.ws.Holder;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Phaser;

public class SparkSoap implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(SparkSoap.class);

    private final Meter meter;
    private final String login;
    private final String pwd;
    private final IFaxWebServiceSoap spark;

    public SparkSoap(Meter meter, String login, String pwd) {
        this.meter = meter;
        this.login = login;
        this.pwd = pwd;

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
        spark = iFaxWebService.getIFaxWebServiceSoap(metricsFeature);

        Map<String, Object> requestContext = ((BindingProvider) spark).getRequestContext();
        requestContext.put(Message.MAINTAIN_SESSION, Boolean.TRUE);
    }

    public void testSparkSoap(int count) {
        Phaser phaser = new Phaser(1);


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
        Throttler throttler = new Throttler(Main.THROTTLE_MS);
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
    }

    @Override
    public void close() {
        spark.end();
    }
}
