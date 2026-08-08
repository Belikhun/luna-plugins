# luna-core-mc26-fabric

The 26.x build of [luna-core-fabric](../luna-core). Same mod, same
sources, different namespace.

Build: `./gradlew :luna-core-mc26-fabric:shadowJar` →
`output/fabric/luna-core-mc26-fabric-all.jar`. There is no `remapJar` here, and
that absence is the entire point of the module.

The toolchain itself is not in this module's build script. Any module named
`<plugin>-mc26-fabric` picks it up from the root `build.gradle.kts`: the game
bundle download, the sibling's source tree and resources, the JDK 25 toolchain
and the fabric-loader/API coordinates. This file only carries what is this
module's own.

## Why a second build exists

Fabric mods are compiled against Mojang names and remapped to **intermediary**,
a stable naming Fabric Loader translates to whatever the running version calls
things. That indirection is what lets one jar serve 1.20 through 1.21.x.

From 26.1 Mojang stopped obfuscating the server. There is nothing left to
translate, so Fabric publishes intermediary as the placeholder version `0.0.0` -
an artifact whose mappings file is one header line and no mappings at all - and
mods are expected to link the game's real names directly. An
intermediary-remapped jar names classes that do not exist on 26.x, so the other
build cannot run there, and a jar built for 26.x names classes that do not exist
below it. Neither is fixable in code; they are two namespaces.

So the code is compiled twice. The 1.20-1.21 module owns the sources and this
module takes them by reference, which means there is no fork to keep in step: a
change lands on both lines or neither.

## What differs

- **No loom.** Nothing here needs remapping, and loom's mapping machinery has
  nothing to work with on this line anyway. The compile classpath is the game's
  own server jar plus the 39 libraries its bundler carries, downloaded from
  Mojang and checked against the sha1 that names it. That is exact by
  construction and cannot go stale against a mapping publication. The download
  is a single root task shared by every `-mc26-fabric` module, so the 70 MB
  lands once no matter how many of them there are.
- **JDK 25 to compile.** 26.x classes are class-file version 69, which only a
  25 javac can read. The bytecode this emits is still Java 21, like every other
  module: the game needing a newer runtime says nothing about what the mod has to
  be compiled to.
- **One duplicated file.** `compat/ChatEvents` is the only place the two game
  lines cannot share an expression - 1.21.5 replaced the concrete `ClickEvent`
  and `HoverEvent` classes with sealed interfaces and a record per action. The
  other half lives in `luna-core-fabric/src/mc21`. Because this line is entirely
  above that change, the server list is always clickable here, where the older
  build degrades to spelling the command out.
- **A different Fabric API build.** Both modules compile against the whole
  artifact, but against their own line's version of it. That is the check on
  reaching for something only one line has: `fabric-permission-api-v1`, for
  instance, exists on this line and not on the other, so using it would fail the
  sibling's compile immediately.

## Verifying it

`tools/check-versions.py` reads every `net.minecraft` member the built jar links
against and resolves it - following supertypes - against the real server jar of
each 26.x release. It is the counterpart of the other module's checker, reading
the game itself rather than a mapping of it, because on this line the game is the
mapping.

```
./gradlew :luna-core-mc26-fabric:shadowJar
python3 luna-core-mc26-fabric/tools/check-versions.py
```

Server jars are cached under `build/servers/`, so only the first run downloads.

## Which one luna deploys

Both. Each jar declares its game range in `luna-plugin.json`, and `luna luna
sync` pools the 1.20-1.21 build as the plugin's primary and this one as a
variant. Deployment then picks per instance by MC version, the same way it
already picks among a plugin's provider builds, so a 1.21.1 backend and a 26.2
backend each get the jar that runs on them without anyone choosing.
