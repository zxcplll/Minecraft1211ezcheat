# Minecraft1211ezcheat

`Minecraft1211ezcheat` is a .NET 10 WPF launcher and Java 21 process agent for
Minecraft 1.21.1. It uses a vendored, pinned build of `nyaoouo/imgui` for an
in-game control surface. It is a process trainer, not a NeoForge mod: nothing
is installed in the game's `mods` directory.

The in-game menu provides movement and interaction tuning, flight permission,
no-clip, fall protection, ore tracking, entity AABB ESP, yellow loaded-chest
treasure highlighting, and Ctrl aim assist.
The external WPF window is deliberately limited to injection status and keeps
the settings heartbeat alive. Multiplayer is left available; a remote server
can still reject or correct client-only movement and damage changes.

## Build

Requirements: Windows, Visual Studio C++ Build Tools, JDK 21, .NET 10 SDK, and
the Minecraft 1.21.1 client libraries available locally for transformer
validation.

```powershell
dotnet publish .\Minecraft1211ezcheat\Minecraft1211ezcheat.csproj `
  -c Release -r win-x64 --self-contained true `
  -p:PublishSingleFile=true -o .\release
```

The build invokes `NativeOverlay/build-native.ps1` and the Gradle agent build.
To validate only the Java transformer, run:

```powershell
Push-Location .\TrainerAgent
.\gradlew.bat clean jar bootstrapJar testClasses
java -cp "build-release\classes\java\test;build-release\classes\java\main;build-release\libs\Minecraft1211ezcheat-agent-2.0.0.jar" `
  io.github.zxcplll.minecraft1211ezcheat.TransformerValidationV6 <mapped-client.jar>
Pop-Location
```

Minecraft must be restarted before attaching a different agent version because
JVM transformers and the native overlay remain loaded for the life of the
process.

## Attribution

The overlay uses the pinned `nyaoouo/imgui` fork documented in
`NativeOverlay/THIRD_PARTY_NOTICES.txt`.
