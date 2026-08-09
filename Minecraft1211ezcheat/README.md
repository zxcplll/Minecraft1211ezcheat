# Minecraft1211ezcheat

这是针对当前 Minecraft Java 进程的本地内存修改器。

- `.NET 10 WPF` 仅显示注入状态，并自动定位正在运行的 Minecraft JVM。
- 内置 Java Attach 代理仅注入当前进程内存，不向游戏 `mods` 目录安装文件。
- 功能设置由游戏内 ImGui 菜单维护，外部窗口通过配置租约保持代理运行。
- 单机集成服务器会同时修改客户端与服务端玩家；多人环境不主动禁用，但可能被远程服务器校验。
- 外部窗口关闭或失去心跳 4 秒后，代理自动恢复原版设置。

## 使用

1. 进入 Minecraft 游戏世界，再启动 `Minecraft1211ezcheat.exe`。
2. 修改器会自动连接当前 Minecraft，无需向 `mods` 目录安装任何文件。
3. 按 Insert 打开游戏内菜单；飞行沿用原版双击空格开关。
4. 关闭修改器后，代理会在租约过期后恢复原版设置。

## 构建

从本项目目录执行：

```powershell
dotnet build -c Release
dotnet publish -c Release -r win-x64 --self-contained true -o '.\publish'
```

发布前需要先生成现有 Java agent/bootstrap JAR 和 Native overlay DLL；本项目会在 MSBuild 资源准备阶段调用对应构建脚本。Java/Native 包名和文件名属于当前运行时兼容接口，迁移时必须与附加入口一起发布。

仅验证 .NET 改动且复用已有运行时资源时，可附加 `-p:SkipRuntimeAssetBuild=true`。
