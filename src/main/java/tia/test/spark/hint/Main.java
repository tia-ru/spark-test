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

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

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
 * Боевой с поддержкой SSL - http://api.spark-interfax.ru/IfaxWebService/iFaxWebService.asmx
 *   https://api.spark-interfax.ru/IfaxWebService/
 *
 * Техническая документация доступна по адресу - https://yadi.sk/d/WWV5gArVguQWgg?w=1
 * Стенд сервиса подсказок - http://hint-demo.spark-interfax.ru/
 *
 * Адрес тестового контура сервиса подсказок (gRPC): http://hint-devel.spark-interfax.ru:50099
 * Также мы разработали вариант REST-сервиса: http://hint-devel.spark-interfax.ru/search
 * Для доступа к сервису подсказок авторизация не требуется.
 * Запросы должны осуществляется с тех IP-адресов, которые привязаны к боевому логину API СПАРК клиента.
 * Документация к сервису подсказок: http://hint-demo.spark-interfax.ru/manual/
 */
public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    private static final int DEFAULT_THROTTLE_MS = 10; //1000 ms/100ms = 10 requests/sec
    private static final int DEFAULT_COUNT = 3;

    private static final String OPT_LOGIN = "l";
    private static final String OPT_PWD = "p";
    private static final String OPT_REQUESTS = "r";
    private static final String OPT_THROTTLE = "t";
    private static final String OPT_SSL = "ssl";
    private static final String OPT_TEST = "test";

    private static final Options OPTIONS = new Options()
            //.addOption("l", true,"Login to SOAP service")
            .addOption(Option.builder(OPT_LOGIN).hasArg().required().desc("Login to SOAP service.").build())
            .addOption(Option.builder(OPT_PWD).hasArg().required().desc("Password to SOAP service.").build())
            .addOption(OPT_TEST, "Use SPARK's public test environment. Otherwise Production is used.")
            .addOption(Option.builder(OPT_REQUESTS).hasArg().desc("Requests count to each service. Default is " + DEFAULT_COUNT + '.').build())
            .addOption(Option.builder(OPT_THROTTLE).hasArg().desc("Requests throttle delay (ms). Default is " + DEFAULT_THROTTLE_MS + " ms.").build())
            .addOption(OPT_SSL, "Use TLS secured channels.")
            ;

    public static void main(String[] args) throws Exception {


        CommandLine cmd = null;
        try {
            cmd = new DefaultParser().parse(OPTIONS, args);
        } catch (ParseException e) {
            System.out.println("ERROR. " + e.getMessage());
            System.out.println();
            printUsage();
            System.exit(1);
        }
        if (cmd.getOptions().length == 0 && cmd.getArgList().isEmpty()) {
            printUsage();
            System.exit(1);
        }

        String login = cmd.getOptionValue(OPT_LOGIN);
        String pwd = cmd.getOptionValue(OPT_PWD);

        String reqCountArg = cmd.getOptionValue(OPT_REQUESTS, String.valueOf(DEFAULT_COUNT));
        int reqCount = Integer.parseInt(reqCountArg);

        String delayArg = cmd.getOptionValue(OPT_THROTTLE, String.valueOf(DEFAULT_THROTTLE_MS));
        int delay = Integer.parseInt(delayArg);

        boolean ssl = cmd.hasOption(OPT_SSL);
        boolean test = cmd.hasOption(OPT_TEST);

        /*if (ssl || !test ){
            System.out.println("SSL and Spark production services are not implemented yet.");
            System.exit(1);
        }*/

        setup();

        Throttler throttler = new Throttler(delay);
        try(Meter meter = new Meter()) {

            /*System.out.println("===================== YANDEX HTTP ==============================================");
            m.testYandex(3);*/

            System.out.println("===================== HINTS REST ===============================================");
            //Warm up
            HintsRest sparkRest = new HintsRest(meter, throttler);
            sparkRest.testSparkRest(reqCount / 5);
            meter.clear();
            sparkRest.testSparkRest(reqCount);
            meter.reportAndClear();
            sparkRest.close();

            System.out.println("===================== HINTS gRPC ===============================================");
            HintsGrpc sparkGrpc = new HintsGrpc(meter, throttler, ssl, test);
            sparkGrpc.testSparkGrpc(reqCount / 5);
            meter.clear();
            sparkGrpc.testSparkGrpc(reqCount);
            meter.reportAndClear();
            sparkGrpc.close();

            System.out.println("===================== SPARK SOAP ===============================================");
            SparkSoap sparkSoap = new SparkSoap(meter, throttler, login, pwd, ssl, test);
            sparkSoap.testSparkSoap(reqCount / 5);
            meter.clear();
            sparkSoap.testSparkSoap(reqCount);
            meter.reportAndClear();
            sparkSoap.close();
        }

    }

    private static void printUsage() {
        HelpFormatter formatter = new HelpFormatter();
        formatter.setSyntaxPrefix("");
        //formatter.setWidth(120);
        formatter.printHelp(
                "Test SPARK services throughput.\n\n",
                "java.exe -Dhttp.proxyHost=<ip> -Dhttp.proxyPort=<port> ^\n" +
                "         -Dhttps.proxyHost=<ip> -Dhttps.proxyPort=<port> ^\n" +
                "         -jar spark-test-1.0.jar ^\n" +
                "         -l <login> -p <password> [-ssl] [-test] [-r <count>] [-t <delay>]\n\n",
                OPTIONS,
                ""
                );
    }

    private static void setup() {

        java.util.logging.Logger rootLogger = LogManager.getLogManager().getLogger("");
        Handler[] handlers = rootLogger.getHandlers();

        for (Handler handler : handlers) {
            rootLogger.removeHandler(handler);
        }
        SLF4JBridgeHandler.install();

    }
}
