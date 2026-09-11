# trace-client

**One call to report that a program was used.**

A dependency-free Java 8 client for a [trace](https://trace.danielstephenson.dev)
server — the central place a fleet of programs reports usage events to. The
whole library is one file, `TraceClient.java`, and the integration on the
program side is meant to stay one call.

```java
TraceClient trace = TraceClient.builder("https://trace.danielstephenson.dev", "MyPlugin")
        .key(config.getString("usage-reporting.key"))
        .enabled(config.getBoolean("usage-reporting.enabled", true))
        .logger(getLogger())
        .build();

trace.report("startup");
trace.report("command", 1.0, Collections.singletonMap("name", "home"));

// on shutdown
trace.close();
```

## What `report` promises

| Property | Meaning |
|---|---|
| **Returns immediately** | The HTTP call runs on one daemon thread the client owns. A Spigot plugin can report from the server thread and no tick waits on the network. |
| **Never throws** | A server that is down, slow, or rejecting the key is a dropped report, not an exception in your program. Drops are logged at `FINE` if you gave a logger, otherwise not at all. |
| **Bounded** | At most 256 reports wait to be sent; past that, new ones are dropped. A trace server that is unreachable for a week costs a few kilobytes, not your heap. |

Reporting is **opt-out**: `enabled(false)`, or no key at all, yields a client
that does nothing and costs nothing. A program that runs on other people's
machines should expose that switch in its configuration.

## Getting it

**Copy the file.** `src/main/java/software/stephenson/trace/TraceClient.java`
has no dependencies and compiles on Java 8. Drop it into your source tree,
keep the header so it can be found again, and you are done — the same way
plugins already vendor bStats' `Metrics.java`.

**Or depend on it** via [JitPack](https://jitpack.io):

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.Stephenson-Software</groupId>
    <artifactId>trace-client-java</artifactId>
    <version>0.1.0</version>
</dependency>
```

Shade it into a plugin jar; it is one class.

## The wire format

`POST {baseUrl}/api/metrics` with `Authorization: Bearer <key>` and a body of

```json
{"application":"MyPlugin","name":"command","value":1.0,"tags":{"name":"home"}}
```

`value` and `tags` are omitted when not given. The server assigns the
timestamp. A `201` is success; anything else is logged at `FINE` and dropped.

## Keys

A key identifies the program to the server and lets the operator revoke it;
it is scoped to *reporting only*. Because it ships inside the program, it
cannot prove anything — treat trace data as best-effort telemetry, which is
what it is. Ask the trace operator for a key for your program.

## Building

```
mvn verify
```

Tests run the client against the JDK's own `HttpServer` on a loopback port —
no more dependencies than the client itself. CI runs them on Java 8, 17 and 21.

## License

MIT.
