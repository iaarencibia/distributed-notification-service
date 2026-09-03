# Servicio de Notificaciones Distribuido

Hub de notificaciones construido con **Java 21** y **Spring Boot 3.5** sobre **PostgreSQL 17**.
Su objetivo es recibir solicitudes por HTTP desde múltiples orígenes, persistirlas y
encolarlas en una única transacción, y despacharlas de forma asíncrona a distintos canales
con reintentos y registro de fallos.

---

## Contenido

- [Precondiciones](#precondiciones)
- [Cómo levantar el proyecto](#cómo-levantar-el-proyecto)
- [Credenciales de prueba](#credenciales-de-prueba)
- [API](#api)
- [Cómo inspeccionar el estado](#cómo-inspeccionar-el-estado)
- [Cómo correr los tests](#cómo-correr-los-tests)
- [Decisiones de diseño](#decisiones-de-diseño)
- [Trade-offs y limitaciones](#trade-offs-y-limitaciones)
- [Estado de la implementación](#estado-de-la-implementación)

---

## Precondiciones

Una sola herramienta:

| Herramienta | Versión mínima | Verificado con |
| --- | --- | --- |
| Docker Engine | 24.0 | 29.7.2 |
| Docker Compose | v2.20 | v5.4.0 |

**No hace falta instalar Java ni Maven.** El proyecto se compila dentro de su propia etapa
de build en el `Dockerfile`, de modo que una máquina sin configuración previa puede
levantarlo igual.

### Sobre la memoria asignada a Docker

El proyecto se desarrolló y se verificó con **8 GB** asignados a Docker. Levantar el stack pide
poco; la parte pesada es la suite de integración, que además del contenedor de Maven y de la JVM
que corre las pruebas arranca un PostgreSQL propio con Testcontainers.

No está medido cuál es el mínimo, así que no se declara uno. Lo que sí se observó: 
con Docker limitado a **1 GB**, los tests de integración pueden fallar **todos** con un
error que nombra `org.mockito.plugins.MockMaker` y no menciona la memoria por ningún lado. Es
fácil leerlo como un defecto del proyecto, y no lo es: es la JVM bifurcada quedándose sin espacio
para instrumentar clases.

### Sobre el intérprete de comandos

Los ejemplos de este documento están escritos para un **shell POSIX**: Linux, macOS, o en
Windows Git Bash o WSL. Ahí se copian y pegan sin tocar nada.

En **PowerShell no funcionan tal cual**, por dos motivos distintos.

El primero es fácil: **`curl` en PowerShell no es curl.** Es un alias de `Invoke-WebRequest`, un
cmdlet con otros parámetros, así que `-i`, `-X` y `-H` no existen para él. Se resuelve escribiendo
`curl.exe`, y usando `` ` `` en lugar de `\` para continuar una línea.

El segundo solo aparece cuando hay un cuerpo JSON. PowerShell 5.1 vuelve a analizar los
argumentos antes de entregárselos a un ejecutable nativo, y **corta el argumento en el primer
espacio que hay dentro de una comilla escapada**. Medido con
`curl --trace-ascii` sobre el ejemplo de la sección *API*: al servidor llegaron **14 bytes**,
`{"subject":"Tu`, y el resto del JSON se perdió por el camino. La respuesta es un `400` que
parece del servicio y es del intérprete.

Por eso el `POST` de la sección *API* está escrito también con **`Invoke-RestMethod`**, que
recibe el cuerpo como una variable y no pasa por ese análisis. Es el único bloque de PowerShell
del documento, y alcanza: la demostración de *[Cómo ver el reintento
ocurriendo](#cómo-ver-el-reintento-ocurriendo)* manda ese mismo cuerpo cambiando el destino y las
claves, así que se adapta desde ahí. Quien prefiera seguir con curl puede anteponer el token
`--%`, que apaga el analizador de PowerShell, a cambio de escribir todo en una sola línea.

En **`cmd`** la continuación de línea es `^` y no `\` —con `\` solo se ejecuta el primer renglón,
y la petición sale sin sus cabeceras—, y las comillas simples no se quitan, así que el cuerpo va
entre comillas dobles con las internas escapadas.

### Versiones que usa el proyecto

Estas versiones están fijadas en el `Dockerfile`, el `pom.xml` y el `docker-compose.yml`;
no hay que instalarlas en la máquina anfitriona.

| Componente | Versión | Dónde está declarada |
| --- | --- | --- |
| Java | 21 (LTS) | `pom.xml` y la imagen base del `Dockerfile` |
| Spring Boot | 3.5.16 | `pom.xml` |
| Maven | 3.9 | Imagen de la etapa de build del `Dockerfile` |
| PostgreSQL | 17 | `docker-compose.yml` |
| WireMock | 3.13.2 | `docker-compose.yml` |

Se usa Java 21 y no una versión más reciente porque es la última LTS sobre la que la línea
3.5 de Spring Boot está plenamente soportada. Se permanece en Spring Boot 3.x, y no se sube
a la línea 4 ya disponible, porque los requisitos del proyecto lo fijan de forma explícita.

---

## Cómo levantar el proyecto

Desde la raíz del repositorio:

```bash
docker compose up --build
```

El primer arranque descarga las imágenes y compila el proyecto, y puede tardar varios
minutos. Los arranques siguientes reutilizan la caché de dependencias de Maven.

El comando queda en primer plano mostrando los registros de los tres servicios, y se detiene
con `Ctrl+C`.

Los servicios que quedan disponibles son:

| Servicio | URL | Rol |
| --- | --- | --- |
| Aplicación | http://localhost:8080 | El servicio de notificaciones |
| PostgreSQL | `localhost:5432` | Persistencia y cola. Credenciales más abajo |
| WireMock | http://localhost:8081 | Servicio externo simulado, destino del canal `SERVICE` |

Comprobar que la aplicación está lista:

```bash
curl http://localhost:8080/actuator/health/readiness
```

La respuesta esperada es:

```json
{"status":"UP"}
```

Para detener todo y borrar los datos:

```bash
docker compose down -v
```

---

## Credenciales de prueba

Todas las credenciales del stack son de desarrollo y tienen un valor por defecto fijado en
`docker-compose.yml`, de modo que un clon limpio funcione sin ningún paso previo.

| Qué | Valor | Variable de entorno que lo cambia |
| --- | --- | --- |
| Clave de la API | `local-dev-api-key-change-me` | `NOTIFICATIONS_API_KEY` |
| PostgreSQL: usuario, contraseña y nombre de la base de datos | `notifications` | `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB` |

**Ninguna es un secreto, y conviene ser exacto sobre por qué.** El stack publica sus puertos en
la máquina anfitriona —`8080`, `5432` y `8081`—, así que no están aisladas: quien alcance esa
máquina por la red puede usarlas, y encima sobre HTTP sin cifrar. Lo que las vuelve inofensivas
no es el aislamiento sino que dan acceso a datos de prueba de un stack efímero. Un despliegue
real las reemplaza antes de exponer nada, y su valor pasa a inyectarse desde un gestor de
secretos.

Las tres variables de PostgreSQL alimentan **al servidor y a la aplicación a la vez**, de modo
que la configuración de ambos no puede quedar escrita con valores distintos. Para cambiar
cualquiera, creá un archivo `.env` en la raíz del repositorio antes de levantar el stack
—Compose lo lee solo, y el mecanismo es idéntico en `cmd`, PowerShell o un intérprete POSIX—:

```
NOTIFICATIONS_API_KEY=otra-clave
POSTGRES_PASSWORD=otra-contraseña
```

El archivo está en `.gitignore`, así que no puede terminar publicado por accidente.

> **Sobre las tres variables de PostgreSQL:** lo que no puede desalinearse es la configuración,
> pero el servidor solo las lee **al inicializar su volumen de datos**, la primera vez que se
> levanta el stack. En un checkout que ya arrancó alguna vez, el servidor sigue con lo que
> guardó entonces aunque los dos servicios estén configurados igual. Cambiar `POSTGRES_USER` o
> `POSTGRES_PASSWORD` hace fallar el arranque de la aplicación con la credencial rechazada;
> cambiar `POSTGRES_DB` lo hace fallar contra una base de datos que no existe. Para que tomen
> efecto hay que descartar el volumen con `docker compose down -v`. La clave de la API no tiene
> ese problema: la aplicación la lee en cada arranque.

### Cómo se presenta la clave

En la cabecera `Authorization`, con el esquema `ApiKey`:

```
Authorization: ApiKey local-dev-api-key-change-me
```

El nombre del esquema no distingue mayúsculas, como exige RFC 9110. La clave no se acepta por
la cadena de consulta a propósito: ahí quedaría escrita en los registros de acceso de
cualquier proxy intermedio y en el historial del cliente.

Toda ruta exige la credencial salvo `/actuator/health` y sus sondas. Sin ella:

```bash
curl -i http://localhost:8080/actuator/metrics
```

```
HTTP/1.1 401
WWW-Authenticate: ApiKey realm="notification-service"
Content-Type: application/problem+json

{"type":"about:blank","title":"Unauthorized","status":401,"detail":"A valid credential using the ApiKey scheme is required in the Authorization header"}
```

La respuesta es la misma ante una credencial ausente y ante una equivocada, a propósito:
distinguirlas le confirmaría a quien está probando claves que la cabecera y el esquema ya eran
correctos.

Con la credencial, la misma petición responde `200`:

```bash
curl -i -H "Authorization: ApiKey local-dev-api-key-change-me" http://localhost:8080/actuator/metrics
```

El sondeo de salud queda abierto porque lo consulta el healthcheck del contenedor, que no
tiene credenciales. Presentarla sobre ese mismo endpoint amplía la respuesta al detalle de
cada componente:

```bash
curl http://localhost:8080/actuator/health
# {"status":"UP","groups":["liveness","readiness"]}

curl -H "Authorization: ApiKey local-dev-api-key-change-me" http://localhost:8080/actuator/health
# {"status":"UP","groups":[...],"components":{"db":{...},"diskSpace":{...}, ...}}
```

---

## API

### `POST /api/v1/notifications`

Acepta una notificación para ser entregada. Responde **`202 Accepted`**, no `201 Created` —y no
porque no se cree nada, la fila se crea—: `201` es una respuesta *sobre la creación*, y lo que el
llamador necesita saber es que la entrega que pidió todavía no ocurrió.

**Cabeceras**

| Cabecera | | |
| --- | --- | --- |
| `Authorization` | obligatoria | `ApiKey <clave>` |
| `Content-Type` | obligatoria | `application/json` |
| `Idempotency-Key` | opcional | Hace la petición segura de repetir. Muy recomendada — ver abajo |
| `X-Correlation-Id` | opcional | Agrupa varias notificaciones de una misma operación. Máx. 128 caracteres, y solo letras, dígitos y `. _ : -`. Si no viene, el servicio genera un UUID |

**Cuerpo**

| Campo | Tipo | | |
| --- | --- | --- | --- |
| `recipient` | `string` | obligatorio | Destino. Una URL para el canal `SERVICE`. Máx. 2048 |
| `channel` | `enum` | obligatorio | `LOG` · `SERVICE`. `EMAIL` existe en el modelo y hoy no se entrega: ver *[Canales de despacho](#canales-de-despacho-log-y-service)* |
| `subject` | `string` | obligatorio | Máx. 512 |
| `body` | `string` | obligatorio | Máx. 16384. Puede ir vacío: un aviso de solo asunto es legítimo |
| `priority` | `enum` | obligatorio | `LOW` · `MEDIUM` · `HIGH` |
| `metadata` | `object` | opcional | Pares de texto, transportados sin tocar. Máx. 32 pares, con la clave en 128 y el valor en 2048 |

Los cuatro límites de texto se cuentan en **caracteres**, no en bytes ni en unidades UTF-16: un
emoji ocupa uno. Un asunto de 512 emojis entra, aunque `String.length()` en Java diga 1024.

```bash
curl -i -X POST http://localhost:8080/api/v1/notifications \
  -H "Authorization: ApiKey local-dev-api-key-change-me" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: pedido-4471-comprador" \
  -H "X-Correlation-Id: order-4471" \
  -d '{
        "recipient": "http://wiremock:8080/hook/ok",
        "channel": "SERVICE",
        "subject": "Tu pedido fue enviado",
        "body": "Va en camino.",
        "priority": "HIGH",
        "metadata": {"orderId": "4471"}
      }'
```

La respuesta esperada es:

```
HTTP/1.1 202
Content-Type: application/json

{"id":"3f2a1c40-9b7e-4d21-8a55-1c0e6f2b91d4"}
```

La respuesta lleva **solo el `id`**, y alcanza: un cliente que no mandó `X-Correlation-Id`
correlaciona por ese identificador, que es único de esta notificación. La cabecera sirve para
agrupar **varias** notificaciones de una misma operación, y eso solo lo sabe quien la envía.

El mismo envío en **PowerShell**. No es `curl` con otra sintaxis: es el cmdlet nativo, y el
motivo está en *[Sobre el intérprete de comandos](#sobre-el-intérprete-de-comandos)*.

```powershell
$body = '{"recipient":"http://wiremock:8080/hook/ok","channel":"SERVICE","subject":"Tu pedido fue enviado","body":"Va en camino.","priority":"HIGH","metadata":{"orderId":"4471"}}'

$headers = @{
  "Authorization"    = "ApiKey local-dev-api-key-change-me"
  "Idempotency-Key"  = "pedido-4471-comprador"
  "X-Correlation-Id" = "order-4471"
}

Invoke-RestMethod -Uri http://localhost:8080/api/v1/notifications -Method Post `
  -ContentType "application/json" -Headers $headers -Body $body
```

> **Hay dos direcciones distintas en los dos ejemplos, y es a propósito.** `localhost:8080` es el
> servicio visto desde tu máquina, que es la que ejecuta el comando. `wiremock:8080` **tu máquina
> no lo resuelve, y no hace falta que lo resuelva**: ese valor no lo usa el cliente, se guarda en
> la fila y lo usa después el contenedor de la aplicación al despachar, desde dentro de la red de
> Docker.
> Los destinos disponibles y cómo invocarlos a mano están en *[Destinos simulados de
> WireMock](#destinos-simulados-de-wiremock)*.

### Por qué conviene mandar `Idempotency-Key`

Si la respuesta se pierde en el camino —un timeout, una conexión cortada—, el cliente no sabe
si la notificación entró. Sin la cabecera, reintentar crea una segunda y el destinatario recibe
el aviso dos veces. Con ella, el reintento **devuelve la misma respuesta y el mismo `id`**, y no
se crea nada.

La clave la elige el cliente, y tiene que identificar el envío lógico, no la petición: la misma
clave para el mismo reintento, una distinta para cada notificación real. Máx. 255 caracteres.

Es distinta de `X-Correlation-Id`, y las dos tiran para lados opuestos. La clave de idempotencia
**no puede repetirse**; el identificador de correlación **debe poder repetirse**, porque las tres
notificaciones de un mismo pedido lo comparten. Mandar `order-4471` como clave de idempotencia en
las tres haría que solo entrara la primera.

### Errores

Todos los errores se devuelven como *problem details* de RFC 9457, con
`Content-Type: application/problem+json`.

| Código | Cuándo |
| --- | --- |
| `400` | Un campo del cuerpo falta o no es válido · un canal o una prioridad que no existen · `X-Correlation-Id` fuera del juego de caracteres permitido · `Idempotency-Key` en blanco o de más de 255 caracteres |
| `401` | Sin credencial, o con una que no coincide |
| `405` · `415` | Método o tipo de contenido equivocados |

El cuerpo de un `400` nombra **cada campo y qué le falta**, que es lo que hace falta para
corregir el cliente sin adivinar:

```bash
curl -i -X POST http://localhost:8080/api/v1/notifications \
  -H "Authorization: ApiKey local-dev-api-key-change-me" \
  -H "Content-Type: application/json" \
  -d '{"recipient": "  ", "channel": "SERVICE"}'
```

```
HTTP/1.1 400
Content-Type: application/problem+json

{"type":"about:blank","title":"Bad Request","status":400,
 "detail":"One or more fields of the request body are invalid",
 "instance":"/api/v1/notifications",
 "errors":{"body":"must be present, though it may be empty",
           "priority":"must be present",
           "recipient":"must not be blank",
           "subject":"must not be blank"}}
```

Las **cabeceras** se responden igual: se revisan las dos antes de rechazar, así que un llamador
que se equivocó en `X-Correlation-Id` y en `Idempotency-Key` se entera de las dos cosas en la
misma respuesta, y cada una bajo el nombre exacto de su cabecera HTTP, tal como la escribió.

```
{"type":"about:blank","title":"Bad Request","status":400,
 "detail":"One or more request headers are invalid",
 "instance":"/api/v1/notifications",
 "errors":{"Idempotency-Key":"must not exceed 255 characters",
           "X-Correlation-Id":"must be 1 to 128 characters, and may contain only letters, digits and the characters . _ : -"}}
```

**Con una excepción, y conviene saberla:** un valor que ni siquiera se puede leer —un canal o
una prioridad que no existen— lo rechaza el deserializador **antes** de que la validación llegue
a correr, así que ese caso se reporta solo:

```
{"type":"about:blank","title":"Bad Request","status":400,
 "detail":"The request body could not be read as JSON",
 "instance":"/api/v1/notifications",
 "errors":{"channel":"must be one of LOG, SERVICE"}}
```

Esa lista es **la de los canales que este despliegue puede entregar**, no la de los valores que
el tipo sabe leer. `EMAIL` es una constante del enum y no tiene adaptador, así que nombrarlo aquí
sería aconsejarle al cliente que mande justo lo que su siguiente petición sería rechazada por
mandar. Un canal apagado y uno que no existe se responden igual porque, desde afuera, son el
mismo problema: pediste algo que no podés usar.

Los errores los producen **dos componentes distintos**, y no es un descuido: el `401` lo escribe
la cadena de seguridad, que rechaza la petición antes de que llegue a ningún controlador, y el
resto lo escribe el manejador del adaptador web. Que los dos coincidan en forma lo fija un test,
no la buena intención.

---

## Cómo inspeccionar el estado

Los logs de la aplicación se emiten como JSON estructurado en formato ECS:

```bash
docker compose logs -f app
```

El estado de las notificaciones vive en la base de datos y se puede consultar directamente:

```bash
docker compose exec postgres psql -U notifications -d notifications -c "SELECT id, channel, status, attempts, next_attempt_at, last_error FROM notification ORDER BY created_at DESC LIMIT 10;"
```

Los valores `-U` y `-d` son los del servicio de PostgreSQL; si los cambiaste con
`POSTGRES_USER` o `POSTGRES_DB`, ajustalos aquí también.

El historial completo de intentos de una notificación:

```bash
docker compose exec postgres psql -U notifications -d notifications -c "SELECT attempt_number, outcome, response_code, duration_ms, error_message FROM notification_attempt WHERE notification_id = '<uuid>' ORDER BY attempt_number;"
```

### Destinos simulados de WireMock

El canal `SERVICE` hace un POST a una URL configurable. Para poder observar el
comportamiento ante fallos sin depender de un servicio externo real, el stack incluye
WireMock con estos destinos precargados.

Estas URL usan el nombre de servicio `wiremock` y el puerto `8080` porque son las que se
indican como destino de una notificación, y quien hace esa llamada es el contenedor de la
aplicación, desde dentro de la red de Docker. Para invocarlas a mano desde la máquina
anfitriona hay que usar el puerto publicado: `http://localhost:8081/hook/...`.

| URL | Respuesta, según el número de llamada recibida | Qué demuestra |
| --- | --- | --- |
| `http://wiremock:8080/hook/ok` | `200` en todas | Despacho exitoso |
| `http://wiremock:8080/hook/flaky` | 1.ª → `503`, 2.ª → `503`, 3.ª → `200` | Fallo transitorio que se recupera: **debe reintentarse** |
| `http://wiremock:8080/hook/broken` | `400` en todas | Rechazo permanente: **no debe reintentarse** |
| `http://wiremock:8080/hook/slow` | `200` tras 15 s | Excede el timeout de lectura |

Las tres respuestas de `/hook/flaky` corresponden a los tres intentos de despacho de **una
misma notificación**, no a tres notificaciones distintas. La secuencia completa es:

| Intento | Respuesta | Consecuencia |
| --- | --- | --- |
| 1 | `503` | Fallo transitorio: se reprograma con backoff |
| 2 | `503` | Fallo transitorio: se reprograma con un backoff mayor |
| 3 | `200` | La notificación pasa a `SENT` |

Está diseñado así a propósito: agota exactamente el presupuesto de reintentos y tiene éxito
en el último intento disponible, que es el caso límite donde suele esconderse un error de
comparación en el contador de intentos.

> El destino `/hook/flaky` usa un escenario con estado: después de tres llamadas queda
> consumido y responde siempre `200`. Para repetir la demostración hay que reiniciarlo:
>
> ```bash
> curl -i -X POST http://localhost:8081/__admin/scenarios/reset
> ```
>
> WireMock responde con un cuerpo vacío, así que conviene el `-i` para ver la confirmación:
> la primera línea de la respuesta debe ser `HTTP/1.1 200 OK`.

### Cómo ver el reintento ocurriendo

Tres notificaciones con **el mismo `X-Correlation-Id`** a tres destinos distintos. Es una sola
demostración y prueba tres cosas: el agrupamiento por operación, la clasificación del fallo, y
que el historial de intentos explica cada desenlace.

El identificador es `order-9001` y no el `order-4471` del ejemplo de *API*, a propósito: si
ejecutaste aquel ejemplo, su fila comparte esa correlación y aparecería en esta consulta.

Primero, reiniciar el escenario de `/hook/flaky`, que es con estado:

```bash
curl -i -X POST http://localhost:8081/__admin/scenarios/reset
```

Después, esta petición **tres veces**, cambiando `<destino>` por `ok`, `flaky` y `broken`. La
clave de idempotencia cambia con el destino porque son tres envíos distintos, no uno repetido:

```bash
curl -i -X POST http://localhost:8080/api/v1/notifications \
  -H "Authorization: ApiKey local-dev-api-key-change-me" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-9001-<destino>" \
  -H "X-Correlation-Id: order-9001" \
  -d '{
        "recipient": "http://wiremock:8080/hook/<destino>",
        "channel": "SERVICE",
        "subject": "Tu pedido fue enviado",
        "body": "Va en camino.",
        "priority": "HIGH"
      }'
```

En **PowerShell** hay que partir del bloque `Invoke-RestMethod` de la sección *API* y cambiar
tres cosas: el `recipient` dentro de `$body`, y la `Idempotency-Key` y el `X-Correlation-Id`
dentro de `$headers`. Con `curl.exe` el cuerpo JSON se corta, por el motivo explicado en
*[Sobre el intérprete de comandos](#sobre-el-intérprete-de-comandos)*.

Las tres responden `202` con el `id` de su notificación. Guardá el de `flaky`.

**Hay que esperar unos 25 segundos**, y el número no es arbitrario: con la configuración que
entrega el proyecto —`initial-backoff` de 5 s y `multiplier` 3.0— el segundo intento de
`/hook/flaky` ocurre 5 s después del primero y el tercero 15 s después del segundo, más el
jitter, que solo suma, y hasta un segundo de sondeo entre cada uno. Consultar antes muestra la
notificación a mitad de camino, en `PENDING` con un intento hecho, que es el estado correcto y
no un fallo.

```bash
docker compose exec postgres psql -U notifications -d notifications -c "SELECT recipient, status, attempts, last_error FROM notification WHERE correlation_id = 'order-9001' ORDER BY recipient;"
```

Salida esperada, con los tres desenlaces que el requisito de resiliencia distingue:

```
            recipient             | status | attempts |                   last_error
----------------------------------+--------+----------+------------------------------------------------
 http://wiremock:8080/hook/broken | FAILED |        1 | the destination rejected the notification with 400
 http://wiremock:8080/hook/flaky  | SENT   |        3 |
 http://wiremock:8080/hook/ok     | SENT   |        1 |
```

Las dos primeras filas son las que importan. `/hook/broken` responde `400` y termina **en un solo
intento**: reintentar una petición que el destino considera mal formada no la vuelve válida.
`/hook/flaky` responde `503` dos veces y termina en `SENT` con tres intentos, porque un `503` es
una condición que puede pasar. La fila de `flaky` no conserva `last_error`: una entrega exitosa
lo limpia, y el historial de sus fracasos vive en la otra tabla.

El historial de la que reintentó, con el `id` que devolvió su `202`:

```bash
docker compose exec postgres psql -U notifications -d notifications -c "SELECT attempt_number, outcome, response_code, duration_ms FROM notification_attempt WHERE notification_id = '<uuid>' ORDER BY attempt_number;"
```

```
 attempt_number |      outcome      | response_code | duration_ms
----------------+-------------------+---------------+-------------
              1 | RETRYABLE_FAILURE |           503 |          12
              2 | RETRYABLE_FAILURE |           503 |           8
              3 | SUCCESS           |           200 |           9
```

Las duraciones son las de una corrida concreta y van a diferir; lo que no cambia es la secuencia.

---

## Cómo correr los tests

Los tests se ejecutan dentro de la misma imagen que compila la aplicación, de modo que no
requieren Java ni Maven instalados en la máquina. Desde la raíz del repositorio:

```bash
docker compose run --rm test
```

El comando es idéntico en cualquier intérprete —`cmd`, PowerShell o un shell POSIX—, ya que
las rutas las resuelve Compose y no el intérprete.

Salida esperada, en dos bloques:

```
[INFO] Tests run: 216, Failures: 0, Errors: 0, Skipped: 0     ← unitarios
[INFO] Tests run: 61, Failures: 0, Errors: 0, Skipped: 0      ← integración
[INFO] BUILD SUCCESS
```

El número de tests crece a medida que avanza la implementación; lo que debe verificarse es
que `Failures` y `Errors` sean cero en los dos.

### Tres avisos esperados, y por qué no se silencian

Conviene separar dos usos de la palabra *falla* que se confunden seguido. **Un test que falla**
es un resultado en rojo y hay trabajo por hacer. **Un test que verifica que algo falla** está en
verde, y lo que falló es el sujeto de la prueba, porque fallar es el comportamiento correcto.
Esta suite tiene los tres avisos que produce esa segunda clase.

**Uno es un stack trace**, y es el único de toda la corrida:

```
WARN  ...DispatchSchedulerConfig -- the dispatch pass failed and will be retried on the next tick
java.lang.IllegalStateException: the database is unreachable
```

Esa excepción la inyecta el test, y su mensaje está escrito a mano. Lo que comprueba
`DispatchSchedulerConfigTest` es que una pasada que revienta **no termina el temporizador**: si la
base de datos se vuelve inalcanzable un minuto, el proceso tiene que seguir sondeando en lugar de
quedarse corriendo y en silencio para siempre. El stack trace aparece porque la excepción se le
pasa al registro a propósito — sin ella, un operador leería *"la pasada falló"* sin ninguna forma
de saber por qué.

En qué orden salen los tres no está fijado: el `pom.xml` no fija `<runOrder>`, así que surefire
toma las clases en el orden del sistema de archivos, y ese orden puede cambiar de una máquina a
otra. Lo que hay que saber reconocer es cuáles son, no dónde aparecen.

Otro es una tanda de `WARN` de `ConfigurationPropertiesBindException`. Son
esperados: `NotificationsPropertiesTest` verifica que una configuración inválida **detiene el
arranque** —un identificador de instancia más largo que su columna, un presupuesto de reintentos
en cero, un intervalo de sondeo en cero, un umbral del reaper en cero, cada bloque ausente— y
para comprobarlo tiene que provocar ese fallo en cada caso. El aviso es la alarma sonando.

Lo que sí sería un problema es ver `Failures` distinto de cero en esa clase: querría decir que un
contexto **arrancó** cuando debía negarse, o sea que la validación al arranque dejó de funcionar.
La garantía la da el número, no el aviso.

El que queda es un contexto que se cancela, y lo emite `ChannelConfigTest`:

```
WARN  ...AnnotationConfigApplicationContext -- Exception encountered during context
initialization - cancelling refresh attempt: ...UnsatisfiedDependencyException: ...
No qualifying bean of type 'org.springframework.web.client.RestClient' available
```

Ese fallo es el resultado que se busca. El `RestClient` del canal SERVICE lleva tiempos de
espera elegidos para un destino que este servicio no controla, y se declara de manera que el
contenedor **no** lo ofrezca a quien pida un `RestClient` por tipo a secas —otra llamada
heredaría cinco segundos de lectura que nadie eligió para ella, y se vería correcta mientras lo
hace—. Para comprobarlo hay que levantar un contexto que lo pida así y verlo negarse.

**Por qué no se silencian.** Se podría bajar el nivel de esos registros con un
`logback-test.xml` de dos líneas. Pero ese archivo pisa la configuración de registro de
**toda** la suite, incluidos los tests de integración que emiten ECS, y apagaría también el aviso
de un contexto que falle por algo **no** previsto — que es justo el que hay que ver. Es comprar
silencio a cambio de ceguera, para ahorrar unas líneas de consola. Se documentan en lugar de
apagarse.

### Los dos niveles

Son dos suites con costos muy distintos, y por eso están separadas.

Los **unitarios** no levantan contenedores, y casi ninguno levanta contexto de Spring. Las
excepciones son tres, y las tres por el mismo motivo —lo que prueban es que el contenedor haga o
deje de hacer algo—: `NotificationsPropertiesTest`, que comprueba que una configuración inválida
impide arrancar; `DispatchSchedulerConfigTest`, que comprueba que el temporizador del
despachador se registra salvo que se lo apague; y `ChannelConfigTest`, que comprueba que el
cliente saliente del canal SERVICE no se le entrega a quien lo pida por tipo a secas. Corren en
segundos y son los que se ejecutan cien veces mientras se trabaja. Los de **integración** arrancan la
aplicación completa contra un PostgreSQL real que Testcontainers levanta para ellos, y prueban
lo que solo la base de datos puede confirmar —el índice único de idempotencia, las restricciones
del esquema, la cadena de filtros sobre un servidor de verdad—.

La separación la hace el nombre de la clase: `*Test` lo ejecuta surefire, `*IT` lo ejecuta
failsafe en una fase posterior.

| Qué se quiere correr | Comando |
| --- | --- |
| Todo | `docker compose run --rm test` |
| Solo los unitarios | `docker compose run --rm test mvn -B -ntp test` |
| Solo los de integración | `docker compose run --rm test mvn -B -ntp verify -DskipUnitTests` |
| Una clase unitaria | `docker compose run --rm test mvn -B -ntp test -Dtest=NotificationStatusTest` |
| Una clase de integración | `docker compose run --rm test mvn -B -ntp verify -DskipUnitTests "-Dit.test=ApiKeySecurityIT"` |

Los argumentos que se añaden reemplazan al comando por defecto del servicio. `-DskipUnitTests`
es una propiedad declarada en el `pom.xml`: el interruptor propio de Maven para esto se llama
`maven.test.skip.exec`, que no es algo que valga la pena pedirle a nadie que recuerde.

Las comillas de la última fila no son adorno: PowerShell corta un argumento sin comillas en el
punto —Maven recibiría `-Dit` y `.test=ApiKeySecurityIT` por separado— y es el único comando de
la tabla con un punto en el nombre de la propiedad. En `cmd` y en un shell POSIX las comillas no
cambian nada, así que la forma entrecomillada sirve para los tres.

Tres notas sobre el servicio `test`:

- Está asignado a un perfil, de modo que **no interviene en `docker compose up`**. Es
  herramienta de desarrollo, no parte del sistema en ejecución.
- Las dependencias se guardan en un volumen con nombre, así que solo la primera ejecución
  paga la descarga. Las siguientes tardan unos segundos.
- Monta el socket de Docker, que es lo que le permite a Testcontainers levantar la base de
  datos de los tests de integración. Los unitarios no lo usan.

---

## Decisiones de diseño

### Outbox transaccional en lugar de un broker de mensajes

**El problema.** Al recibir una solicitud hay que hacer dos cosas: persistir la
notificación y avisarle al despachador que hay trabajo pendiente. Si esas dos cosas viven
en sistemas distintos —PostgreSQL y un broker— no comparten transacción, y aparece el
problema de la *doble escritura*: la persistencia puede tener éxito y la publicación
fallar, dejando una notificación que nadie despachará jamás; o al revés, publicarse un
mensaje que apunta a una fila cuya transacción terminó en rollback.

**La decisión.** La tabla `notification` es simultáneamente el registro de la notificación
y la cola en la que espera. Persistir y encolar ocurren en la misma transacción ACID, de
modo que esa ventana de inconsistencia no existe.

Un worker reclama trabajo con:

```sql
SELECT ... FROM notification
WHERE status = 'PENDING' AND next_attempt_at <= now()
ORDER BY priority_rank, next_attempt_at, created_at
LIMIT :batch
FOR UPDATE SKIP LOCKED
```

`SKIP LOCKED` es lo que permite que varias instancias consulten en paralelo y cada una se
lleve un conjunto disjunto de filas, sin bloquearse entre sí. Es el patrón de *competing
consumers* sin necesidad de un broker. El `:batch` es un parámetro enlazado en tiempo de
ejecución: cuántas filas reclama cada instancia por sondeo, configurable en
`notifications.dispatch.batch-size`.

**Alternativas descartadas.**

- **RabbitMQ.** Introduce la doble escritura descrita arriba. Resolverla correctamente
  obliga a implementar un outbox de todas formas, con lo cual se suman las dos
  complejidades en lugar de sustituir una por otra. Además agrega un contenedor más al
  stack, y el sistema debe funcionar en una máquina sin configuración previa: cada pieza
  adicional es una posibilidad más de que `docker compose up` falle.
- **Cola en memoria (`BlockingQueue` con `@Async`).** Es lo más rápido de escribir y pierde
  todo lo encolado ante un reinicio del proceso, lo que incumple el requisito de
  resiliencia.

**Qué se pierde con esta elección.** El sondeo impone un piso a la latencia de despacho
igual a su intervalo; se puede reducir con `LISTEN/NOTIFY` de PostgreSQL, a costa de más
código. La carga de sondeo recae sobre la base de datos, que se convierte en el techo de
escalabilidad del sistema. Y no hay fan-out: si en el futuro varios consumidores
necesitaran reaccionar al mismo evento, un broker sería la herramienta adecuada.

### Arquitectura hexagonal

La forma del problema coincide con la del patrón: dos entradas que disparan la misma
lógica —la API REST y el worker de despacho— y varias salidas intercambiables —los canales
de notificación—. Eso es exactamente lo que *puertos y adaptadores* modela.

```
src/main/java/io/github/iaarencibia/notifications/
├── domain/          Modelo y reglas del ciclo de vida. Java puro, sin frameworks.
├── application/
│   ├── port/in/     Lo que el exterior puede pedirle a este servicio.
│   ├── port/out/    Lo que este servicio necesita del exterior, en términos de dominio.
│   └── service/     Casos de uso.
├── adapter/
│   ├── in/          Adaptadores que conducen la aplicación (REST, scheduler).
│   └── out/         Adaptadores conducidos por ella (persistencia, canales).
└── config/          Composition root: seguridad, cliente HTTP, pools, configuración tipada.
```

Se usa el vocabulario propio del patrón (`adapter`) en lugar del de la arquitectura por
capas de DDD (`infrastructure`), por dos motivos. El primero es que `adapter` hace pareja
con `port`, y la relación entre ambos queda explícita en el nombre. El segundo, más
importante, es que la división `in` / `out` expresa la **dirección del control**: un
adaptador de entrada invoca a la aplicación, uno de salida es invocado por ella. Son
relaciones opuestas, y un único paquete `infrastructure` las confundiría.

Del diseño táctico de DDD se toma lo que aporta —agregado, value objects, repositorio como
puerto— y se deja fuera lo que sería ceremonia en este alcance: no hay eventos de dominio,
porque el outbox ya cumple ese rol, ni mapa de contextos, porque hay un único contexto.

**Qué cuesta.** Hexagonal agrega indirección: interfaces de puerto y traducción entre el
modelo de dominio y el de persistencia. En un servicio con un único adaptador de salida
sería sobrecosto injustificado. Aquí se paga con varios canales intercambiables y dos
puntos de entrada.

### Autenticación por clave de API

**El problema.** El endpoint de creación escribe en la base de datos y genera trabajo
asíncrono, de modo que no puede quedar abierto. Hay que elegir un mecanismo, y el criterio no
es cuál es más seguro en abstracto sino **quién es el llamador y qué tiene que transportar la
credencial**. Aquí el llamador es otro servicio: no hay persona, ni consentimiento, ni un
tercero actuando en nombre de nadie.

**La decisión.** Una clave compartida que viaja en la cabecera `Authorization` con el esquema
`ApiKey`, verificada por un filtro dentro de la cadena de Spring Security. El filtro se limita
a autenticar; **qué puede alcanzar cada petición lo decide la cadena**, en un único lugar, de
forma que la política de acceso se lee de una sola mirada en vez de estar repartida.

Esa política es denegar por defecto: todo exige la clave excepto `/actuator/health` y sus
sondas, que quedan abiertas porque el healthcheck del contenedor las consulta sin
credenciales. Es más estricto de lo que hoy se necesita, y a propósito: un endpoint que se
añada mañana nace protegido sin que nadie tenga que acordarse de protegerlo.

Viaja en `Authorization` y no en una cabecera propia como `X-API-Key` por dos motivos. El
primero es que `Authorization` es el lugar que la especificación reserva para credenciales, lo
que permite acompañar el `401` con su `WWW-Authenticate: ApiKey realm="notification-service"`;
una cabecera propia no tiene ningún desafío que anunciar, de modo que el rechazo quedaría
incompleto frente a RFC 9110. El segundo es que los proxies, los recolectores de registros y
los agentes de observabilidad enmascaran `Authorization` por convención, mientras que una
cabecera propia solo se enmascara si alguien se acuerda de configurarlo. En un servicio que
emite registros estructurados, esa diferencia es la que separa una credencial protegida de una
credencial escrita en el registro.

El esquema se llama `ApiKey` y no `Bearer` porque este último está definido para los tokens de
acceso de OAuth 2.0, que es justamente lo que se descarta más abajo; nombrarlo así abriría una
ambigüedad innecesaria. `ApiKey` no figura en el registro de IANA —el nombre del esquema es un
token extensible, así que es válido igualmente— y describe exactamente lo que se presenta.

La comparación se hace en tiempo constante. Un `equals` entre cadenas termina en cuanto
encuentra el primer carácter distinto, de modo que el tiempo de respuesta revela cuántos
caracteres iniciales acertó quien la envió.

**Alternativas descartadas.**

- **OAuth2.** Es un protocolo de delegación: existe para que una persona autorice a una
  aplicación de terceros a actuar en su nombre sin entregarle su contraseña. Aquí no hay
  dueño de los recursos ni hay tercero. Su flujo `client_credentials` sí cubre el caso de
  máquina a máquina, pero equivale a una clave de API con pasos intermedios salvo que hagan
  falta credenciales de vida corta, permisos diferenciados por cliente o revocación
  centralizada entre varios servicios. Con un solo llamador y una sola API, el servidor de
  autorización aporta todo el coste y ninguna de esas tres cosas: un contenedor más, y un
  *realm* y un cliente que sembrar antes de que el sistema responda, lo que incumple el
  requisito de que `docker compose up` baste en una máquina sin configuración previa.
- **JWT.** No es un mecanismo de autenticación sino un formato para transportar
  afirmaciones firmadas. Su ventaja —que quien recibe la petición pueda validarla sin
  consultar a quien la emitió— se cobra con varios servicios y un proveedor de identidad
  común. Con un único servicio se estarían firmando afirmaciones que él mismo emite y él
  mismo lee. Y añade un coste propio: un token es válido hasta que expira, así que revocar
  uno comprometido antes de tiempo exige mantener una lista de rechazados, es decir
  exactamente el estado compartido que el formato venía a eliminar.

**Qué cuesta.** La clave no caduca ni rota sin un redespliegue. Es una sola para todos los
llamadores, de modo que no se puede atribuir una petición a uno concreto ni retirarle el
acceso sin afectar a los demás. Y es una credencial *al portador*: quien la posee queda
autenticado, por lo que su protección en tránsito depende por completo de TLS, que este stack
local no ofrece.

**Qué haría cambiar la decisión.** Varios llamadores que necesiten revocación independiente
llevarían a una clave por cliente, almacenada como *hash* en la base de datos. Terceros
actuando en nombre de usuarios llevarían a OAuth2. Varios servicios compartiendo una misma
identidad, a JWT con un emisor común. En los tres casos el cambio queda contenido en la
cadena de seguridad: de ahí hacia adentro nadie lee la credencial. Y en el caso de JWT el
cliente tampoco cambia dónde la coloca: la misma cabecera `Authorization`, con el esquema
`Bearer` en lugar de `ApiKey`.

### Canales de despacho: `LOG` y `SERVICE`

El proyecto requiere al menos dos canales, con `LOG` siempre presente. El segundo elegido es
`SERVICE` en lugar de `EMAIL` porque es el único que ejercita de verdad la distinción entre
un fallo transitorio y uno permanente, que es el núcleo del requisito de resiliencia:

| Respuesta del destino | Clasificación |
| --- | --- |
| `2xx` | Éxito |
| `429` | Fallo transitorio, respetando el `Retry-After` que el destino pida |
| Cualquier otro `4xx` | Fallo permanente: no reintentar |
| `5xx`, timeout, conexión rechazada, host que no resuelve | Fallo transitorio: reintentar con *backoff* |

El orden de esa tabla es el orden en que se evalúa, y `429` va antes que el resto de los `4xx` a
propósito: es un `4xx` por número y un fallo transitorio por significado. El destino no está
rechazando el mensaje, está pidiendo menos tráfico. Clasificado con su familia numérica,
quemaría una notificación por la única razón que se resuelve sola.

Cualquier código que no sea ninguno de esos —un `3xx`, un `1xx`— se trata como transitorio. El
error caro es descartar una notificación que la próxima vez habría entregado.

`Retry-After` se lee **solo en su forma de segundos**. La forma de fecha HTTP es legal y se
ignora a propósito: honrarla es confiar en el reloj del destino contra el nuestro, y un desfase
ahí se convierte en una notificación durmiendo horas. Ausente o ilegible significa "sin
instrucción", y la política de reintentos usa su propio *backoff*, que nunca es peor que lo que
habría hecho igual.

Un servidor SMTP de pruebas como Mailhog acepta prácticamente cualquier mensaje, por lo que
no permite demostrar esa diferencia. Con WireMock, en cambio, el reintento es observable: una
sola notificación enviada a `/hook/flaky` produce una secuencia de intentos que queda escrita en
`notification_attempt`. Está en *[Cómo ver el reintento ocurriendo](#cómo-ver-el-reintento-ocurriendo)*.

#### Qué recibe el destino del canal `SERVICE`

Un `POST` con `Content-Type: application/json`, el contenido que envió el cliente, y dos
cabeceras:

| Cabecera | Para qué |
| --- | --- |
| `X-Notification-Id` | Identifica **esta notificación**. Es lo que le permite al destino descartar una entrega que ya aceptó, si este servicio reintentó una que en realidad había llegado |
| `X-Correlation-Id` | Agrupa la notificación con el resto de su operación, del lado del destino también |

```json
{
  "recipient": "http://wiremock:8080/hook/ok",
  "subject": "Tu pedido fue enviado",
  "body": "Va en camino.",
  "priority": "HIGH",
  "metadata": {"orderId": "4471"}
}
```

**No viaja nada del ciclo de vida** —ni el estado, ni el número de intento, ni el presupuesto—.
Eso es asunto de este servicio, y un destino que recibiera un contador de intentos estaría
leyendo un estado sobre el que no tiene voz. Lo único que necesita para actuar es el contenido y
el identificador con el que reconocer un repetido.

Ambos *timeouts* son obligatorios y se configuran en `notifications.channels.service`. El de
lectura es el que más importa: el de conexión solo cubre a un destino que nunca acepta la
conexión, y un destino que **acepta y después se queda callado** es el fallo más común, y el más
caro, porque retiene un trabajador mientras dura.

#### `EMAIL` existe en el modelo y está apagado

`Channel` tiene sus tres valores porque el enunciado nombra tres, y el `CHECK` de la columna los
admite. Lo que no existe es un adaptador que lo entregue, así que **el ingreso lo rechaza con un
`400`** en lugar de aceptarlo.

Aceptarlo sería peor que rechazarlo: la respuesta sería un `202` y la fila quedaría fallando en
cada intento hasta agotar su presupuesto. Al cliente se le habría dicho que su entrega fue
aceptada, y nunca lo fue.

Qué se puede entregar **sale del registro de canales**, no de una lista escrita a mano: es el
conjunto de adaptadores conectados al arrancar. De ahí lo leen tanto el caso de uso, que decide,
como el manejador de errores, que redacta el mensaje —así la decisión y su explicación no pueden
separarse—. El día que se agregue una implementación de `EMAIL`, empieza a aceptarse y los
mensajes de error cambian solos, sin tocar ninguna validación.

### Esquema de base de datos

El esquema lo gestiona Flyway. Hibernate está configurado con `ddl-auto: validate`, de modo
que cualquier divergencia entre una entidad y su migración detiene el arranque en lugar de
manifestarse en tiempo de ejecución.

**Una migración aplicada no se toca.** Flyway guarda el *checksum* de cada archivo que ejecutó,
así que editar uno ya aplicado no es una mala práctica que alguien podría señalar: es un fallo
de arranque, inmediato y ruidoso. Mientras el esquema no se haya aplicado en ningún entorno que
importe, corregir `V1` en el sitio y descartar el volumen con `docker compose down -v` es más
limpio que arrastrar una migración correctiva por algo que nunca existió afuera. Desde el primer
despliegue real eso se termina, y todo cambio —incluso agregar una columna— es un archivo nuevo.

Cuatro detalles del diseño de la tabla merecen mención. Los dos primeros son sobre cómo se
consulta; los dos últimos, sobre qué pasa cuando dos instancias escriben a la vez.

**`priority_rank` es una columna generada.** Ordenar por la columna textual `priority`
produce un orden alfabético en el que `'LOW'` precede a `'MEDIUM'`, invirtiendo en silencio
la prioridad de despacho. Derivar el rango numérico en la base de datos garantiza que no pueda
desincronizarse del valor del que depende.

**El índice de reclamo es parcial** sobre `status = 'PENDING'`. Solo las filas pendientes se
consultan, de modo que el índice permanece proporcional al trabajo acumulado y no al
histórico completo.

**El historial de intentos es único por número**, y eso hace más que higiene. El reaper no
distingue una instancia muerta de una lenta: si el destino tarda más que el umbral, la fila se
libera, otra instancia la entrega, y la primera vuelve con una copia vieja del estado. La
restricción `uk_attempt_notification_number` es lo que la frena, porque el número de intento se
deriva de `attempts` —el campo que quedaría viejo—, así que quien vuelve tarde calcula un
número que el otro ya usó. Como el intento y la actualización de la notificación van en la misma
transacción, el choque revierte las dos, y una entrega exitosa no queda pisada por el resultado
tardío de quien perdió la carrera.

**La columna `version` existe para esa misma carrera, declarada en vez de heredada.** La
protección de arriba es correcta pero indirecta: depende de que el número de intento se derive de
`attempts`, y se evaporaría el día que alguien los asignara con una secuencia. Un contador de
escrituras hace explícito el bloqueo optimista, y convierte "perdí la carrera" en un evento con
nombre propio en lugar de un error de integridad indistinguible de un defecto.

El despachador la usa así: la consulta de reclamo devuelve, junto a cada notificación, **la
versión que la fila tenía al ser reclamada**, y el resultado de la entrega se escribe contra ese
número. Tiene que ser ése y no el actual —contra el valor de ahora la guarda pasaría siempre—.
Si el reaper liberó la fila mientras esa instancia despachaba, su `UPDATE` ya movió la versión:
la escritura tardía se rechaza, y el despachador registra que perdió la carrera en lugar de pisar
el estado que dejó quien la ganó.

### Ciclo de vida de una notificación

```
                    ┌──────────────────────────────┐
                    │                              │
                    ▼                              │
   POST ──────▶ PENDING ──claim──▶ DISPATCHING ────┤
                    ▲                    │         │
                    │                    ├──────▶ SENT      (terminal)
                    │                    │
                    │                    └──────▶ FAILED    (terminal)
                    │                              │
                    └──── reaper (claim vencido) ──┘
```

`DISPATCHING` existe para que el bloqueo de fila no tenga que sostenerse durante el
despacho. La transacción que reclama la notificación se cierra de inmediato, liberando la
conexión antes de cualquier operación de red; el estado actúa entonces como un bloqueo
lógico persistido. La contrapartida es que una instancia que muera a mitad de un despacho
dejaría la fila reclamada indefinidamente, y por eso existe el *reaper*: un proceso que
devuelve a `PENDING` toda fila cuyo reclamo haya superado un umbral de antigüedad.

Esto implica una semántica de entrega **at-least-once**: si el proceso muere después de
entregar pero antes de registrar el resultado, el reintento producirá una entrega
duplicada. La entrega *exactly-once* no es alcanzable —entre realizar la llamada y
registrar que se realizó siempre hay una brecha en la que el proceso puede caerse—, de
modo que lo máximo que puede lograrse es *effectively once*: un emisor que entrega al
menos una vez y un receptor que descarta lo repetido.

El diseño aborda esa duplicación en los dos extremos, y los dos mecanismos están implementados:

- **Hacia afuera**, cada entrega del canal `SERVICE` lleva el identificador de la
  notificación en una cabecera `X-Notification-Id`, idéntica en el primer intento y en
  cada reintento. Un receptor que la registre puede descartar la entrega repetida. Sin ese
  identificador no podría hacerlo aunque quisiera, de modo que enviarlo es la parte del
  problema que sí está bajo control de este servicio.
- **Hacia adentro**, el endpoint de creación acepta una cabecera `Idempotency-Key`
  opcional, porque este servicio es a su vez un receptor: si la respuesta a un `POST` se
  pierde en la red, un cliente prudente reintentará y crearía una segunda notificación. La
  columna `idempotency_key` y su índice único parcial ya existen en el esquema, de forma
  que la exclusión quede garantizada por la base de datos y no por el código. Es opcional
  a propósito: solo el cliente sabe si dos peticiones son el mismo envío lógico, y
  exigirla llevaría a que quien no le da importancia genere una distinta en cada llamada,
  lo que elimina la protección conservando su apariencia.

El *reaper* se ejecuta como una única sentencia `UPDATE` en bloque, y no cargando cada
notificación vencida para liberarla de a una. Es **la única transición del ciclo de vida que
no atraviesa el modelo de dominio**, y la excepción es deliberada: liberar *N* reclamos
caducados cuesta una consulta en lugar de *N*, y devolver una fila a `PENDING` por vencimiento
es una tarea de mantenimiento del sistema, no una regla de negocio de la notificación. Lo que
protege esa escritura es el check constraint `ck_notification_status`, que rechazaría un
estado inválido con independencia del camino por el que llegue.

---

## Trade-offs y limitaciones

Además del coste que se detalla junto a cada decisión, hay cosas que quedaron fuera de forma
consciente. Se enumeran aquí con el problema concreto que dejan abierto.

### Los límites de tamaño son una elección, y el enunciado no los pedía

El documento define los campos por su tipo —`String`, `Map<String,String>`— y no da tamaños para
ninguno. Los cuatro números de `body` y `metadata` los elegí yo, leyendo lo que sí dice: *"el
contenido del **mensaje**"* y *"**campos** adicionales opcionales"*. Un mensaje no es un
documento, y unos campos adicionales se cuentan.

El criterio fue el techo **más bajo que nunca rechaza una petición legítima**, no el más alto que
el sistema aguanta. Un techo demasiado alto deja de proteger.

El precio es real: un cliente con un cuerpo legítimamente enorme —un informe embebido, digamos—
se topa con un `400` en vez de un envío. Si ese caso apareciera, la respuesta correcta no sería
subir el número sino separar el contenido del aviso: guardar el informe en otro lado y mandar su
enlace, que es lo que un canal de notificaciones hace bien.

La alternativa —no poner techo— no es neutral, aunque lo parezca. Sin él, quien decide cuánto
almacenamiento consume el servicio es el llamador.

### No hay *circuit breaker*

Es la ausencia más significativa. Los reintentos se calculan por notificación y de manera
independiente entre sí: si un destino está caído, cada notificación dirigida a él gasta sus
tres intentos por su cuenta. Con mil notificaciones hacia el mismo endpoint inaccesible, el
sistema realiza tres mil llamadas contra algo que no responde.

Eso tiene dos consecuencias. Se castiga a un servicio que ya está degradado, en lugar de
darle margen para recuperarse; y el pool de despacho se consume esperando tiempos de espera
que se sabe de antemano que van a agotarse, lo que retrasa el despacho hacia destinos que sí
funcionan.

La solución sería un cortacircuitos por host: tras *N* fallos consecutivos contra el mismo
destino, dejar de intentar durante un intervalo y reprogramar sus notificaciones sin
consumirles intentos. Se descartó por alcance —implica una dependencia adicional, estado por
host y decidir el tratamiento de las notificaciones mientras el circuito está abierto—, y
ese tiempo se destinó a los tests y a la documentación.

### Una notificación fallida no puede reencolarse

`FAILED` es un estado terminal: no existe operación que devuelva una notificación agotada a
`PENDING`. Tras una interrupción prolongada de un destino, las notificaciones que consumieron
sus intentos quedan registradas con su motivo, pero no vuelven a intentarse. Recuperarlas
exige una modificación manual sobre la base de datos.

La solución sería un endpoint de reintento explícito. Se dejó fuera porque reabrir un estado
terminal plantea decisiones que no son mecánicas: si el contador de intentos se reinicia o se
amplía el presupuesto, qué ocurre cuando la operación se invoca dos veces, y quién queda
autorizado a hacerlo. Resolverlas a medias produciría un mecanismo peor que su ausencia.

La contrapartida de mantenerlo terminal es que el recuento no se reinicia: ningún reencolado
puede bajar `attempts` para disimular los intentos que ya se hicieron.

### `max_attempts` acota los intentos registrados, no las llamadas de red

`attempts` cuenta **entregas registradas**, y esa cifra puede quedar por debajo de la cantidad
real de peticiones que salieron de este servicio.

El caso es el mismo que hace falta el reaper. Una instancia reclama una notificación, hace el
`POST`, y muere —o tarda más de lo que el reaper tolera— antes de escribir el resultado. El
intento ocurrió: el destino recibió la petición y pudo haberla procesado. Pero no quedó
registrado, así que cuando el reaper devuelve la fila a `PENDING` el contador sigue donde estaba
y la notificación conserva su presupuesto entero.

La consecuencia práctica es que un destino puede recibir más de `max_attempts` peticiones de una
misma notificación. Es la otra cara de la semántica *at-least-once* declarada más arriba: el
sistema prefiere entregar de más antes que perder una entrega. Lo que sí queda acotado con
firmeza es el trabajo que el servicio se compromete a hacer **y a dejar asentado**.

Cerrarlo del todo exige idempotencia del lado del receptor, que está fuera de nuestro control.
Lo que se ofrece para eso es la cabecera `X-Notification-Id` de cada entrega, con la que un
destino puede descartar lo que ya aceptó.

### Los destinos del canal `SERVICE` no están restringidos

El canal `SERVICE` envía un POST a la URL que el cliente indique en `recipient`, sea cual
sea. Un servidor que realiza peticiones a direcciones arbitrarias suministradas desde fuera
es susceptible de *server-side request forgery*: el atacante no ataca directamente, sino que
utiliza este servicio como intermediario para alcanzar lo que él no alcanza.

Un cliente autenticado podría indicar como destino
`http://169.254.169.254/latest/meta-data/`, la dirección de metadatos de las instancias en
la nube, o cualquier servicio interno sin exposición pública. La aplicación, que sí tiene
acceso a la red privada, realizaría esa petición en su nombre.

El diseño lo agrava: `notification_attempt` conserva el código de respuesta y la duración de
cada intento, de modo que incluso sin devolver el contenido se informa de qué hay al otro
lado. Una conexión rechazada, un tiempo de espera agotado y un `401` son tres respuestas
distintas que permiten cartografiar la red interna.

Un despliegue real requeriría tres controles: una lista de hosts de destino autorizados;
el bloqueo de los rangos privados y de enlace local —en particular `169.254.0.0/16`—
admitiendo solo los esquemas `http` y `https`; y la comprobación sobre la **dirección IP ya
resuelta** y no sobre el nombre del host, ya que un dominio bajo control del atacante puede
apuntar a una dirección interna.

Se acepta como limitación deliberada porque el único destino previsto forma parte del propio
stack, y la autenticación restringe quién puede enviar notificaciones.

### Un llamador sin credencial no distingue una ruta protegida de una inexistente

Sin credencial, `GET /actuator/metrics` —que existe y está protegida— y `GET /no-existe`
responden **las dos `401`**. Devolver el estado exacto de cada una le entregaría a cualquiera un
mapa del servicio: bastaría con recorrer un diccionario de rutas para saber cuáles existen. Se
eligió el silencio, y lo fija un test.

El precio es que un llamador anónimo no puede distinguir *"no tenés permiso"* de *"eso no
existe"*. Con un catálogo de rutas públicas más grande, la decisión se revisaría.

La única excepción es `/actuator/health`, abierta a propósito y documentada como tal: ahí un
anónimo sí recibe la respuesta exacta —`200` a un `GET`, `405` a un `POST`—. No se pierde nada,
porque su existencia nunca fue un secreto.

### El canal `EMAIL` no está implementado

Se eligieron `LOG` y `SERVICE`, y el motivo está explicado más arriba. Incorporar `EMAIL`
consiste en añadir una implementación del puerto de canal y un servidor SMTP de pruebas al
stack; no requiere cambios en el dominio ni en los casos de uso.

El precio, y es real: **un cliente que pida `EMAIL` recibe un `400`** aunque el enunciado liste
ese canal entre los posibles. Se prefirió eso a aceptarlo y no entregarlo nunca, que le mentiría
al llamador en el peor momento —cuando cree que su aviso salió—. El rechazo nombra los canales
que sí funcionan, así que el cliente sabe qué hacer con la respuesta.

### Una clave de idempotencia repetida no compara el cuerpo de la petición

Si llega dos veces la misma `Idempotency-Key` con contenidos distintos, se devuelve la
notificación original sin advertir la diferencia. El comportamiento riguroso sería responder
`409 Conflict`, lo que exige almacenar una huella del cuerpo de cada petición.

### No hay purga de datos históricos

Las notificaciones y sus intentos se acumulan indefinidamente. A largo plazo haría falta
archivado o particionado por fecha, y una caducidad para las claves de idempotencia. El
índice de reclamo es parcial precisamente para que el crecimiento del histórico no degrade
el trabajo del despachador, pero eso mitiga el problema sin resolverlo.

---

## Estado de la implementación

| Componente | Estado |
| --- | --- |
| Estructura del proyecto y arquitectura hexagonal | Completo |
| Esquema de base de datos y migraciones | Completo |
| Stack de Docker Compose | Completo |
| Modelo de dominio y máquina de estados | Completo |
| Persistencia de notificaciones | Completo |
| API REST de creación (`POST /api/v1/notifications`) | Completo |
| Idempotencia de entrada (`Idempotency-Key`) | Completo |
| Autenticación | Completo |
| Tests unitarios | Completo |
| Tests de integración | Completo |
| API REST de consulta (`GET /api/v1/notifications/{id}`) | Pendiente — el estado se consulta hoy contra la base de datos, como muestra *Cómo inspeccionar el estado* |
| Canales de despacho (`LOG` y `SERVICE`) | Completo |
| Worker de despacho, reintentos y *reaper* | Completo |
| Canal `EMAIL` | Pendiente — el ingreso lo rechaza en lugar de aceptar algo que no puede enviar |
| Idempotencia de salida (`X-Notification-Id`) | Completo — el canal `SERVICE` la envía en cada entrega |
| Consideración sobre Jakarta EE | Pendiente |
