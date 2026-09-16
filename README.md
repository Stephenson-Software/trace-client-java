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
        .serverWideConfig(getDataFolder().getParentFile()) // plugins/ -- Bukkit plugins only
        .logger(getLogger())
        .build();

// Say so, every startup, on the program's own logger.
if (trace.isEnabled()) {
    getLogger().info("Usage reporting is on: MyPlugin sends its name, version and command names to "
            + "https://trace.danielstephenson.dev - nothing about players or the server. "
            + "Turn it off with usage-reporting.enabled: false in this plugin's config.yml, "
            + "or for every plugin with enabled: false in plugins/trace/config.yml. "
            + "Details: https://github.com/Stephenson-Software/trace#usage-reporting");
} else {
    getLogger().info("Usage reporting is off (" + trace.disabledReason() + ").");
}

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
| **`close()` drains** | Reports already queued get up to the client timeout (5 s total) to be sent before the thread stops, so a CLI that reports and exits at once does not lose its event. Still bounded: an unreachable server delays exit by at most the timeout. |

## Opting out

Reporting is **opt-out**, and the person running the program always has the
last word. `build()` checks these in order; the first match wins and is what
`disabledReason()` returns, verbatim, so the program can log it:

| Switch | `disabledReason()` |
|---|---|
| Environment: `TRACE_USAGE_REPORTING=off` (or `false`, `0`, `no`) or `DO_NOT_TRACK=1` (or `true`, `yes`), case-insensitive. Always checked. | `environment` |
| Server-wide, when `serverWideConfig(pluginsDirectory)` was given: `enabled: false` in `plugins/trace/config.yml`. `build()` creates the file with `enabled: true` if it is missing and never rewrites it afterwards; it is read with a line regex, no YAML library. An IO failure is logged at `FINE` and counts as enabled. | `server-wide config: plugins/trace/config.yml` |
| The program's own setting: `enabled(false)`. | `config.yml` |
| No key, or a blank one. | `no key` |

`disabledReason()` is `null` when the client is enabled. A disabled client does
nothing and costs nothing. A program that runs on other people's machines
should expose its own switch in its configuration and print, on every
startup, whether reporting is on and how to turn it off — see the example
above and the [usage reporting](https://github.com/Stephenson-Software/trace#usage-reporting)
page for the wording the fleet uses.

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
    <version>0.2.0</version>
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
