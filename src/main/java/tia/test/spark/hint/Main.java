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
public class Main {

    public static final int THROTTLE_MS = 20; //1000 ms/100ms = 10 requests/sec
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    private static final int DEFAULT_COUNT = 3;

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

        setup();


        try(Meter meter = new Meter()) {

            /*System.out.println("===================== YANDEX HTTP ==============================================");
            m.testYandex(3);*/

            System.out.println("===================== SPARK HTTP REST ==========================================");
            //Warm up
            SparkRest sparkRest = new SparkRest(meter);
            sparkRest.testSparkRest(reqCount / 5);
            meter.clear();
            sparkRest.testSparkRest(reqCount);
            meter.reportAndClear();
            sparkRest.close();

            System.out.println("===================== SPARK gRPC ===============================================");
            SparkGrpc sparkGrpc = new SparkGrpc(meter);
            sparkGrpc.testSparkGrpc(reqCount / 5);
            meter.clear();
            sparkGrpc.testSparkGrpc(reqCount);
            meter.reportAndClear();
            sparkGrpc.close();

            System.out.println("===================== SPARK SOAP ===============================================");
            SparkSoap sparkSoap = new SparkSoap(meter, login, pwd);
            sparkSoap.testSparkSoap(reqCount / 5);
            meter.clear();
            sparkSoap.testSparkSoap(reqCount);
            meter.reportAndClear();
            sparkSoap.close();
        }

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
