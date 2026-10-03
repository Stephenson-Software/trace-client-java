# trace-client

**One call to report that a program was used.**

A dependency-free Java 8 client for a [trace](https://trace.danielstephenson.dev)
server — the central place a fleet of programs reports usage events to. The
whole library is one file, `TraceClient.java`, and the integration on the
program side is meant to stay one call.

```java
TraceClient trace = TraceClient.builder("https://trace.danielstephenson.dev", "MyPlugin",
                getDescription().getVersion())
        .key(config.getString("usage-reporting.key"))
        .enabled(config.getBoolean("usage-reporting.enabled", true))
        .serverWideConfig(getDataFolder().getParentFile()) // plugins/ -- Bukkit plugins only
        .logger(getLogger())
        .build();

// Say so, every startup, on the program's own logger.
if (trace.isEnabled()) {
    getLogger().info("Usage reporting is on: MyPlugin sends its name, version, command names and a "
            + "random server ID to https://trace.danielstephenson.dev - nothing about players, and "
            + "nothing that identifies the server's owner or address. "
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

## Every event carries the program's version

The third argument to `builder` is the program's own version, and it is
required: a blank one, or one over 255 characters, throws
`IllegalArgumentException`. Every event the client sends — `startup`,
`command`, anything else — carries it as the tag `version`, so every event
can be tied to a release, not just `startup`. An event that passes its own
`version` tag keeps it. There is no need to tag `startup` by hand any more.

Before 0.4.0, `builder` took two arguments and only events tagged by hand
carried a version. Upgrading is one argument: in a Bukkit plugin,
`getDescription().getVersion()`.

## Every event carries a random server ID

Since 0.5.0, every event also carries the tag `install`: a random ID for
the installation, so the trace server can count **distinct servers** ("active
servers in the last 30 days") rather than raw events. This is the same idea
as bStats' `serverUuid`, and it is said out loud here because it is the one
thing the client sends that is the same from one event to the next.

**What it is.** A `UUID.randomUUID()`. It is not derived from anything — not
a hostname, an IP address, a MAC address, a player, an account or a path. It
identifies no person and no address; all it can say is "these events came
from the same server". (The trace server still sees the IP address of every
HTTP request, as every web server does.)

**Where it lives.** On a Spigot server, as the `server-id:` line of
`plugins/trace/config.yml`, shared by every plugin on that server. The first
time an *enabled* client finds no `server-id:` there, it appends one, under a
comment that says what it is:

```yaml
#
# server-id: a random ID made on first run and sent as the tag "install", so
# trace can count servers, not events. It identifies no person and no IP
# address. Delete the server-id line to get a new one.
server-id: 0f8b6c1e-3a52-4c8e-9a0d-6e2f1b7c4d90
```

Nothing else in the file is touched: comments, `enabled:` and `tags:` are
kept byte for byte. If the file cannot be read or written, a fresh ID is used
in memory for that run only — `build()` still never throws.

**Resetting it.** Delete the `server-id:` line; the next start writes a new
one. Or set your own value (letters, digits, `_`, `.`, `-`; at most 255
characters).

**Opting out.** Every [opt-out](#opting-out) below also stops the ID: a
disabled client never generates one, never writes one, and sends nothing.
There is no way to send events without it short of turning reporting off;
that is deliberate, so "how many servers" is a number that can be trusted.

**Programs that are not plugins.** Without `serverWideConfig(...)`, no ID is
made up and no hidden file is written anywhere unless the program asks for
one. Since 0.6.0 a program can name a file to keep the ID in — the program
chooses it; there is no default location:

```java
TraceClient trace = TraceClient.builder(url, "mycli", version).key(key)
        .enabled(settings.usageReportingEnabled())
        .installIdFile(new File(dataDir, "trace-install-id")) // e.g. ~/.local/share/mycli/
        .build();
```

The first time an *enabled* client starts, it writes a new random UUID to
that file (creating parent directories) and reuses it on every later run.
The first line that is an ID (`[A-Za-z0-9_.-]`, at most 255 characters) is
the one used. If the file cannot be read or written, a fresh ID is used in
memory for that run only — `build()` never throws over it, and a file that
exists but cannot be read is never overwritten. Delete the file to reset it,
or put your own value on its first line.

A program that already keeps its own settings can pass the ID instead:

```java
TraceClient.builder(url, "MyCli", version).key(key)
        .installId(config.getString("usage-reporting.install-id")) // null or blank: none sent
        .build();
```

**Precedence.** An explicit `installId(...)` wins over `installIdFile(...)`,
which wins over the server-wide `server-id:` (the server-wide file then gains
no `server-id:` line; its `enabled:` switch and `tags:` still apply). An
event that passes its own `install` tag keeps it. `trace.installId()` returns
the ID in use (`null` when disabled or when there is none), so a program can
print it.

`TraceClient.installIdFromFile(file)` is the same load-or-create step on its
own, for a program that wants the ID for something else. **Called directly,
it writes the file whatever the opt-outs say** — pass the file to
`installIdFile(...)` to keep the guarantee that a disabled client never
generates, reads or writes an ID.

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
| Server-wide, when `serverWideConfig(pluginsDirectory)` was given: `enabled: false` in `plugins/trace/config.yml`. `build()` creates the file with `enabled: true` (and a commented-out [`tags:`](#server-wide-tags) example) if it is missing; afterwards the only change it ever makes is appending a [`server-id:`](#every-event-carries-a-random-server-id) line when an enabled client finds none. It is read with a line regex, no YAML library. An IO failure is logged at `FINE` and counts as enabled. | `server-wide config: plugins/trace/config.yml` |
| The program's own setting: `enabled(false)`. | `config.yml` |
| No key, or a blank one. | `no key` |

`disabledReason()` is `null` when the client is enabled. A disabled client does
nothing and costs nothing. A program that runs on other people's machines
should expose its own switch in its configuration and print, on every
startup, whether reporting is on and how to turn it off — see the example
above and the [usage reporting](https://github.com/Stephenson-Software/trace#usage-reporting)
page for the wording the fleet uses.

## Server-wide tags

The same `plugins/trace/config.yml` can carry a `tags:` block. Every event
every plugin on that server reports gets these tags added — the way a test or
CI server marks itself so its events are left out of real-installation
figures (the trace server's public numbers exclude `ci`, `service` and `page`):

```yaml
enabled: true
tags:
  ci: "true"
```

- `tags:` starts at column 0 and is followed by indented `key: value` lines.
  Values may be double-quoted, single-quoted or bare; blank lines and `#`
  comments inside the block are skipped. The block ends at the next line that
  is not indented, or at the end of the file.
- An event's own tag always wins, and the program's version is the event's
  own: a server-wide `version` never overwrites it.
- Entries the trace server would reject are dropped one by one, never the
  whole report: keys must match `[A-Za-z0-9][A-Za-z0-9_.-]*`, keys and values
  are at most 255 characters, and server-wide tags stop being added once an
  event carries 32 tags in total. Anything the line reader does not
  understand (flow maps, lists, block scalars, a quote never closed) is
  dropped the same way; a malformed file never throws and never turns
  reporting off.
- The tags are read once, in `build()`, together with `enabled:`.
  `enabled: false` still wins — a disabled client sends nothing, tags or not.
- A file created by `build()` has the example above commented out, so nothing
  is added until the operator uncomments it.

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
    <version>0.6.0</version>
</dependency>
```

Shade it into a plugin jar; it is one class.

## The wire format

`POST {baseUrl}/api/metrics` with `Authorization: Bearer <key>` and a body of

```json
{"application":"MyPlugin","name":"command","value":1.0,"tags":{"name":"home","version":"1.4.0","install":"0f8b6c1e-3a52-4c8e-9a0d-6e2f1b7c4d90"}}
```

`value` is omitted when not given; `tags` always holds at least `version`, and
`install` whenever the client has an [installation ID](#every-event-carries-a-random-server-id). The server assigns the
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
