package tia.test.spark.soap;

import org.apache.cxf.frontend.ClientProxy;
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
import org.apache.cxf.metrics.micrometer.provider.jaxws.JaxwsFaultCodeProvider;
import org.apache.cxf.metrics.micrometer.provider.jaxws.JaxwsFaultCodeTagsCustomizer;
import org.apache.cxf.metrics.micrometer.provider.jaxws.JaxwsOperationTagsCustomizer;
import org.apache.cxf.metrics.micrometer.provider.jaxws.JaxwsTags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.interfax.ifax.GetCompanyShortReportResponse;
import ru.interfax.ifax.IFaxWebService;
import ru.interfax.ifax.IFaxWebServiceSoap;
import tia.test.spark.common.Meter;
import tia.test.spark.common.Throttler;

import javax.xml.ws.BindingProvider;
import javax.xml.ws.Holder;
import javax.xml.ws.WebServiceFeature;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Phaser;

/* Трубуется получить в СЭД:
•	полное наименование контрагента (для ИП - фамилия И.О. ИП);
•	краткое наименование контрагента;
•	ИНН;
•	КПП;
•	ОГРН / ОГРНИП;
•	ОКПО;
•	Адрес места нахождения (для ИП – адрес места жительства);
•	основная отрасль (ОКВЭД);
•	код региона (ОКАТО);
•	наименование региона;
•	ФИО руководителя организации;
•	наименование кода ОКОПФ;
•	код ОКОПФ.

 */
public class SparkSoap implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(SparkSoap.class);

    private final Meter meter;
    private final String login;
    private final String pwd;
    private final boolean ssl;
    private final boolean test;
    private final IFaxWebServiceSoap sparkMeasured;
    private final IFaxWebServiceSoap spark;
    private final Throttler throttler;

    public SparkSoap(Meter meter, Throttler throttler, String login, String pwd, boolean ssl, boolean test) {
        this.meter = meter;
        this.login = login;
        this.pwd = pwd;
        this.ssl = ssl;
        this.test = test;
        this.throttler = throttler;

        try {
            final JaxwsTags jaxwsTags = new JaxwsTags();
            final TagsCustomizer operationsCustomizer = new JaxwsOperationTagsCustomizer(jaxwsTags);
            final TagsCustomizer faultsCustomizer = new JaxwsFaultCodeTagsCustomizer(jaxwsTags, new JaxwsFaultCodeProvider());

            final MetricsProvider metricsProvider = new MicrometerMetricsProvider(
                    meter.getRegistry(),
                    new StandardTagsProvider(new DefaultExceptionClassProvider(), new StandardTags()),
                    Arrays.asList(operationsCustomizer, faultsCustomizer),
                    new DefaultTimedAnnotationProvider(),
                    new MicrometerMetricsProperties());


            IFaxWebService iFaxWebService;
            if (test) {
                iFaxWebService = new IFaxWebService();
            } else {
                URL wsdlLocation;
                if (ssl){
                    wsdlLocation = new URL("https://api.spark-interfax.ru/IfaxWebService/iFaxWebService.asmx?wsdl");
                } else {
                    wsdlLocation = new URL("http://api.spark-interfax.ru/IfaxWebService/iFaxWebService.asmx?wsdl");
                }
                iFaxWebService = new IFaxWebService(wsdlLocation);
            }
            MetricsFeature metricsFeature = new MetricsFeature(metricsProvider);
            sparkMeasured = createSparkService(iFaxWebService, ssl, metricsFeature);
            spark = createSparkService(iFaxWebService, ssl);

            Object address = ClientProxy.getClient(sparkMeasured).getContexts().getRequestContext().get(Message.ENDPOINT_ADDRESS);
            logger.info("Address: {}", address);


        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    protected IFaxWebServiceSoap createSparkService(IFaxWebService iFaxWebService, boolean ssl, WebServiceFeature... features) throws MalformedURLException {
        IFaxWebServiceSoap spark = iFaxWebService.getIFaxWebServiceSoap12(features);

        Map<String, Object> requestContext = ((BindingProvider) spark).getRequestContext();
        requestContext.put(Message.MAINTAIN_SESSION, Boolean.TRUE);
        if (ssl) {
            URL ep = new URL((String) requestContext.get(Message.ENDPOINT_ADDRESS));
            if (!ep.getProtocol().equals("https")){
                URL sslEp = new URL("https", ep.getHost(), ep.getPort(), ep.getFile());
                requestContext.put(Message.ENDPOINT_ADDRESS, sslEp.toString());
            }
        }
        /*
        Client client = ClientProxy.getClient(spark);
        HTTPConduit http = (HTTPConduit) client.getConduit();
        HTTPClientPolicy httpClientPolicy = new HTTPClientPolicy();

        httpClientPolicy.setConnectionTimeout(36000);
        httpClientPolicy.setAllowChunking(false);
        httpClientPolicy.setReceiveTimeout(32000);

        http.setClient(httpClientPolicy);*/
        return spark;
    }

    public void testSparkSoap(int count) {
        Phaser phaser = new Phaser(1);
        Random rnd = new Random();

        List<String> randomSparkIds = getRandomSparkIds();
        if (randomSparkIds.isEmpty()){
            return;
        }

        String result = sparkMeasured.authmethod(login, pwd);
        if (!"True".equals(result)) {
            logger.error("AuthN failed. Result: {}", result);
            return;
        }
        for (int i = 0; i < count; i++) {
            Holder<String> resultHolder = new Holder<>();
            Holder<String> xmlDataHolder = new Holder<>();
            phaser.register();
            throttler.pause();

            //spark.getCompanyContactsAsync();
            String sparkId = randomSparkIds.get(rnd.nextInt(randomSparkIds.size()));
            sparkMeasured.getCompanyShortReportAsync(sparkId, null, null, resultHolder, xmlDataHolder,
                    response -> {
                        try {
                            GetCompanyShortReportResponse r = response.get();
                            logger.debug("Result: {}", r.getGetCompanyShortReportResult());
                            logger.debug("xml: {}", r.getXmlData());
                        } catch (InterruptedException | ExecutionException e) {
                            logger.error("SparkSoap error", e);
                        }
                        phaser.arriveAndDeregister();
                    });
        }

        phaser.arriveAndAwaitAdvance();
    }

    private List<String> getRandomSparkIds(){
        String result = spark.authmethod(login, pwd);
        if (!"True".equals(result)) {
            logger.error("AuthN failed. Result: {}", result);
            return Collections.emptyList();
        }
        Holder<String> resultHolder = new Holder<>();
        Holder<String> xmlDataHolder = new Holder<>();

        spark.getCompanyListByName("Интер", "45", "0", "1", resultHolder, xmlDataHolder);
        logger.debug("Result: {}", resultHolder.value);
        logger.debug("xml: {}", xmlDataHolder.value);

        if ("True".equals(resultHolder.value)){
            return extractIds(xmlDataHolder.value);
        }

        return Collections.emptyList();
    }

    private List<String> extractIds(String xml) {
        List<String> result = new ArrayList<>(3000);
        int offset = 0;
        String PREF = "<SparkID>";
        String SUF = "</SparkID>";
        int i = xml.indexOf(PREF);
        int j = xml.indexOf(SUF);
        for(; i >=0 && j >=0 && offset < xml.length(); i = xml.indexOf(PREF, offset), j = xml.indexOf(SUF, offset)){
            String id = xml.substring(i + PREF.length(), j);
            result.add(id);
            offset = j + SUF.length();
        }
        return result;
    }

    @Override
    public void close() {
        sparkMeasured.end();
    }
}
