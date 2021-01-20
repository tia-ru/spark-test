# Тест производительности 3 API СПАРК

Проверяется производительность поиска организаций по имени с помощью 
- HTTP REST протокола сервиса подсказок
- gRPC протокола сервиса подсказок
- Метода `GetCompanyListByName` SOAP протокола основного API 
   
### Cборка
Cборка `spark-test-1.0.jar`:

```
mvn package
```
### Запуск
Для получения краткой справки по параметрам:

```
java -jar spark-test-1.0.jar 
```

##  Сервис СПАРК:
 - Тестовый - http://sparkgatetest.interfax.ru/IfaxWebService/ifaxwebservice.asmx
 - Боевой - http://webservicefarm.interfax.ru/IfaxWebService/ifaxwebservice.asmx
 - Боевой с поддержкой SSL/TLS - https://api.spark-interfax.ru/IfaxWebService/iFaxWebService.asmx   
 - Техническая документация: https://yadi.sk/d/WWV5gArVguQWgg?w=1
 
## Сервис подсказок:
 - Адрес тестового сервиса подсказок (gRPC): http://hint-devel.spark-interfax.ru:50099
 - Адрес тестового сервиса подсказок (REST): http://hint-devel.spark-interfax.ru/search
 - Адрес боевого сервиса подсказок (gRPC): http://hint.spark-interfax.ru:50093
 - Адрес боевого сервиса подсказок (REST): http://hint.spark-interfax.ru/search
 - Техническая документация: http://hint-demo.spark-interfax.ru/manual/
 - UI сервиса подсказок: http://hint-demo.spark-interfax.ru/

gRPC не поддерживает SSL/TLS на данный момент. 
