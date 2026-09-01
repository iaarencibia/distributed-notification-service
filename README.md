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

> **Si usás PowerShell**, escribí `curl.exe` en lugar de `curl`: en esa shell `curl` es un
> alias de `Invoke-WebRequest` y rechaza estos parámetros. En Bash, WSL, Git Bash y CMD los
> ejemplos funcionan tal cual.

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

---

## Cómo correr los tests

Los tests se ejecutan dentro de la misma imagen que compila la aplicación, de modo que no
requieren Java ni Maven instalados en la máquina. Desde la raíz del repositorio:

```bash
docker compose run --rm test
```

El comando es idéntico en cualquier intérprete —`cmd`, PowerShell o un shell POSIX—, ya que
las rutas las resuelve Compose y no el intérprete.

Salida esperada:

```
[INFO] Results:
[INFO]
[INFO] Tests run: 130, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

El número de tests crece a medida que avanza la implementación; lo que debe verificarse es
que `Failures` y `Errors` sean cero.

Para ejecutar una única clase, los argumentos que se añaden reemplazan al comando por
defecto del servicio:

```bash
docker compose run --rm test mvn -B -ntp test -Dtest=NotificationStatusTest
```

Dos notas sobre el servicio `test`:

- Está asignado a un perfil, de modo que **no interviene en `docker compose up`**. Es
  herramienta de desarrollo, no parte del sistema en ejecución.
- Las dependencias se guardan en un volumen con nombre, así que solo la primera ejecución
  paga la descarga. Las siguientes tardan unos segundos.

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
| `5xx`, timeout, conexión rechazada | Fallo transitorio: reintentar con backoff |
| `400`, `404`, `422` | Fallo permanente: no reintentar |
| `429` | Fallo transitorio, respetando `Retry-After` |

Un servidor SMTP de pruebas como Mailhog acepta prácticamente cualquier mensaje, por lo que
no permite demostrar esa diferencia. Con WireMock, en cambio, el reintento es observable:
una sola notificación enviada a `/hook/flaky` produce una secuencia visible de intentos.

`EMAIL` queda fuera del alcance entregado. Gracias al puerto de canal, incorporarlo consiste
en añadir una implementación y un contenedor al stack, sin tocar el dominio ni los casos de
uso.

### Esquema de base de datos

El esquema lo gestiona Flyway. Hibernate está configurado con `ddl-auto: validate`, de modo
que cualquier divergencia entre una entidad y su migración detiene el arranque en lugar de
manifestarse en tiempo de ejecución.

Dos detalles del diseño de la tabla merecen mención:

**`priority_rank` es una columna generada.** Ordenar por la columna textual `priority`
produce un orden alfabético en el que `'LOW'` precede a `'MEDIUM'`, invirtiendo en silencio
la prioridad de despacho. Derivar el rango numérico en la base de datos garantiza que no pueda
desincronizarse del valor del que depende.

**El índice de reclamo es parcial** sobre `status = 'PENDING'`. Solo las filas pendientes se
consultan, de modo que el índice permanece proporcional al trabajo acumulado y no al
histórico completo.

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

El diseño aborda esa duplicación en los dos extremos. **La implementación de ambos
mecanismos está pendiente**, pero la decisión ya está tomada y el esquema la refleja:

- **Hacia afuera**, cada entrega del canal `SERVICE` llevará el identificador de la
  notificación en una cabecera `X-Notification-Id`, idéntica en el primer intento y en
  cada reintento. Un receptor que la registre puede descartar la entrega repetida. Sin ese
  identificador no podría hacerlo aunque quisiera, de modo que enviarlo es la parte del
  problema que sí está bajo control de este servicio.
- **Hacia adentro**, el endpoint de creación aceptará una cabecera `Idempotency-Key`
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

La contrapartida de mantenerlo terminal es que el recuento permanece auditable: `attempts`
nunca se reinicia, de modo que ningún reencolado puede ocultar los intentos ya realizados.

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

### Un llamador sin credencial no distingue un error de una ruta inexistente

Cuando una petición termina en error, el contenedor la redespacha internamente a `/error`, y
esa ruta queda denegada como todas las demás. La consecuencia visible: `POST /actuator/health`
—un método no admitido sobre la única ruta abierta— responde `401` en lugar del `405` que
correspondería. Con credencial válida sí devuelve `405` con su cabecera `Allow: GET`.

Es deliberado. Abrir el redespacho de error devolvería el estado correcto, y de paso le
entregaría a cualquiera un mapa del servicio: una ruta inexistente pasaría a responder `404`
mientras una ruta real y protegida sigue en `401`, y bastaría con recorrer un diccionario para
saber qué existe. Hoy las dos responden `401` y no se pueden separar.

Se eligió el silencio. El precio es un código de estado impreciso sobre un endpoint público
invocado con el método equivocado, cosa que ningún cliente del sistema hace —el healthcheck
del contenedor usa `GET`—; el beneficio es que la superficie no se puede cartografiar sin
credencial. Con un catálogo de rutas públicas más grande, la decisión se revisaría.

### El canal `EMAIL` no está implementado

Se eligieron `LOG` y `SERVICE`, y el motivo está explicado más arriba. Incorporar `EMAIL`
consiste en añadir una implementación del puerto de canal y un servidor SMTP de pruebas al
stack; no requiere cambios en el dominio ni en los casos de uso.

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
| API REST de creación y consulta | Pendiente |
| Idempotencia de entrada (`Idempotency-Key`) y de salida (`X-Notification-Id`) | Pendiente |
| Autenticación | Completo |
| Worker de despacho y canales | Pendiente |
| Tests unitarios del dominio | Completo |
| Tests de integración | Pendiente |
| Consideración sobre Jakarta EE | Pendiente |
