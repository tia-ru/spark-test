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
Для запуска теста в поддиректории target выполнить:
```
java -Dhttp.proxyHost= -Dhttp.proxyPort= -Dhttps.proxyHost= -Dhttps.proxyPort= -jar spark-test-1.0.jar <login> <password> [<requests count>] 
```
, где 

`proxyHost` и `proxyPort` --  адрес http-прокси имеющего зарегистрированный в СПАРК внешний IP для сервиса подсказок.

`requests count` -- количество поисковых запросов к каждому API

`login` `password` -- логин и пароль для доступа к SOAP API СПАРК.

