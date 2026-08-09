using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using Minecraft1211ezcheat.Models;

namespace Minecraft1211ezcheat.Services;

public sealed class GameConnectionService : IDisposable
{
    private const string RequiredAgentVersion = "2.0.0";
    private const string AgentResourceName = "Minecraft1211ezcheat.Resources.agent.jar";
    private const string BootstrapResourceName = "Minecraft1211ezcheat.Resources.bootstrap.jar";
    private const string NativeResourceName = "Minecraft1211ezcheat.Resources.overlay.dll";
    private const string ImGuiLicenseResourceName = "Minecraft1211ezcheat.Resources.imgui-LICENSE.txt";
    private const string ThirdPartyNoticesResourceName = "Minecraft1211ezcheat.Resources.THIRD_PARTY_NOTICES.txt";
    private readonly GamePaths paths;
    private readonly TrainerConfigService configService;
    private readonly string agentPath;
    private readonly string bootstrapPath;
    private readonly string nativePath;
    private int lastAttemptProcessId;
    private DateTimeOffset lastAttemptUtc = DateTimeOffset.MinValue;
    private string? lastAttachError;

    public GameConnectionService(GamePaths paths, TrainerConfigService configService)
    {
        this.paths = paths;
        this.configService = configService;
        agentPath = ExtractResource(AgentResourceName, "agent", ".jar");
        bootstrapPath = ExtractResource(BootstrapResourceName, "bootstrap", ".jar");
        nativePath = ExtractResource(NativeResourceName, "overlay", ".dll");
        ExtractLicense(ImGuiLicenseResourceName, "imgui-LICENSE.txt");
        ExtractLicense(ThirdPartyNoticesResourceName, "THIRD_PARTY_NOTICES.txt");
    }

    public async Task<ConnectionSnapshot> CheckAsync(CancellationToken cancellationToken = default)
    {
        using var gameProcess = FindMinecraftProcess();
        if (gameProcess is null)
        {
            return new ConnectionSnapshot(ConnectionState.Offline, "游戏未运行", "启动 Minecraft 后会自动连接");
        }

        if (lastAttemptProcessId != 0 && lastAttemptProcessId != gameProcess.Id)
        {
            lastAttemptProcessId = 0;
            lastAttemptUtc = DateTimeOffset.MinValue;
            lastAttachError = null;
        }

        var status = configService.ReadBridgeStatus();
        if (status is { Active: true }
            && status.Version == RequiredAgentVersion
            && status.ProcessId == gameProcess.Id
            && status.HasFreshHeartbeat(TimeSpan.FromSeconds(4)))
        {
            if (!string.IsNullOrWhiteSpace(status.Error))
            {
                return new ConnectionSnapshot(ConnectionState.Error, "内存代理运行异常", status.Error);
            }

            lastAttachError = null;
            var playerName = status.PlayerNames.FirstOrDefault();
            if (!status.PlayerReady)
            {
                return new ConnectionSnapshot(
                    ConnectionState.Starting,
                    "内存代理已连接",
                    "等待进入游戏世界",
                    playerName);
            }

            return status.Singleplayer
                ? new ConnectionSnapshot(ConnectionState.Connected, "已连接单机世界", "内存修改已同步", playerName)
                : new ConnectionSnapshot(ConnectionState.Connected, "已连接多人游戏", "远程服务器可能校验修改", playerName);
        }

        if (lastAttemptProcessId == gameProcess.Id
            && DateTimeOffset.UtcNow - lastAttemptUtc < TimeSpan.FromSeconds(8))
        {
            return lastAttachError is null
                ? new ConnectionSnapshot(ConnectionState.Starting, "正在附加内存代理", $"Java PID {gameProcess.Id}")
                : new ConnectionSnapshot(ConnectionState.Error, "无法连接游戏", lastAttachError);
        }

        lastAttemptProcessId = gameProcess.Id;
        lastAttemptUtc = DateTimeOffset.UtcNow;
        var attachError = await AttachAsync(gameProcess, cancellationToken);
        lastAttachError = attachError;
        return attachError is null
            ? new ConnectionSnapshot(ConnectionState.Starting, "内存代理已附加", "正在等待游戏响应")
            : new ConnectionSnapshot(ConnectionState.Error, "无法连接游戏", attachError);
    }

    private async Task<string?> AttachAsync(Process gameProcess, CancellationToken cancellationToken)
    {
        string? javaPath;
        try
        {
            javaPath = gameProcess.MainModule?.FileName;
        }
        catch (Exception exception) when (exception is InvalidOperationException or System.ComponentModel.Win32Exception)
        {
            return "无法读取 Java 进程路径";
        }

        if (string.IsNullOrWhiteSpace(javaPath) || !File.Exists(javaPath))
        {
            return "未找到游戏使用的 Java 运行时";
        }

        var startInfo = new ProcessStartInfo
        {
            FileName = javaPath,
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true
        };
        startInfo.ArgumentList.Add("--add-modules");
        startInfo.ArgumentList.Add("jdk.attach");
        startInfo.ArgumentList.Add("-cp");
        startInfo.ArgumentList.Add(agentPath);
        string attachEntryPoint;
        try
        {
            attachEntryPoint = ReadMainClassFromJar(agentPath)
                ?? throw new InvalidDataException("Java agent manifest does not declare Main-Class");
        }
        catch (Exception exception) when (exception is IOException or InvalidDataException)
        {
            return $"Java 内存代理入口无效：{exception.Message}";
        }
        startInfo.ArgumentList.Add(attachEntryPoint);
        startInfo.ArgumentList.Add(gameProcess.Id.ToString());
        startInfo.ArgumentList.Add(agentPath);
        startInfo.ArgumentList.Add(bootstrapPath);
        startInfo.ArgumentList.Add(paths.SettingsPath);
        startInfo.ArgumentList.Add(paths.StatusPath);
        startInfo.ArgumentList.Add(nativePath);

        using var attacher = Process.Start(startInfo);
        if (attacher is null)
        {
            return "无法启动 Java 附加进程";
        }

        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(TimeSpan.FromSeconds(15));
        try
        {
            await attacher.WaitForExitAsync(timeout.Token);
        }
        catch (OperationCanceledException)
        {
            if (!attacher.HasExited)
            {
                attacher.Kill(true);
            }
            return "Java 附加超时";
        }

        var standardOutput = await attacher.StandardOutput.ReadToEndAsync(cancellationToken);
        var standardError = await attacher.StandardError.ReadToEndAsync(cancellationToken);
        if (attacher.ExitCode == 0 && standardOutput.Contains("ATTACHED", StringComparison.Ordinal))
        {
            return null;
        }

        var detail = string.IsNullOrWhiteSpace(standardError) ? standardOutput : standardError;
        detail = detail.ReplaceLineEndings(" ").Trim();
        return detail.Length > 140 ? detail[..140] : detail;
    }

    private string ExtractResource(string resourceName, string kind, string extension)
    {
        Directory.CreateDirectory(paths.RuntimeDirectory);
        using var resource = Assembly.GetExecutingAssembly().GetManifestResourceStream(resourceName)
            ?? throw new InvalidOperationException("修改器内存代理资源缺失");
        using var memory = new MemoryStream();
        resource.CopyTo(memory);
        var bytes = memory.ToArray();
        var hash = Convert.ToHexStringLower(SHA256.HashData(bytes))[..16];
        var targetPath = Path.Combine(paths.RuntimeDirectory, $"minecraft1211ezcheat-{kind}-{hash}{extension}");
        if (File.Exists(targetPath))
        {
            return targetPath;
        }

        var temporaryPath = Path.Combine(paths.RuntimeDirectory, $"agent.{Guid.NewGuid():N}.tmp");
        File.WriteAllBytes(temporaryPath, bytes);
        File.Move(temporaryPath, targetPath, false);
        return targetPath;
    }

    private static string? ReadMainClassFromJar(string jarPath)
    {
        using var archive = ZipFile.OpenRead(jarPath);
        var manifest = archive.GetEntry("META-INF/MANIFEST.MF")
            ?? throw new InvalidDataException("Java agent manifest is missing");
        using var reader = new StreamReader(manifest.Open(), Encoding.UTF8, detectEncodingFromByteOrderMarks: true);
        while (reader.ReadLine() is { } line)
        {
            const string prefix = "Main-Class:";
            if (line.StartsWith(prefix, StringComparison.OrdinalIgnoreCase))
            {
                var className = line[prefix.Length..].Trim();
                if (className.Length > 0)
                {
                    return className.Replace('/', '.');
                }
            }
        }

        return null;
    }

    private void ExtractLicense(string resourceName, string fileName)
    {
        var licenseDirectory = Path.Combine(paths.RuntimeDirectory, "licenses");
        Directory.CreateDirectory(licenseDirectory);
        var targetPath = Path.Combine(licenseDirectory, fileName);
        using var resource = Assembly.GetExecutingAssembly().GetManifestResourceStream(resourceName)
            ?? throw new InvalidOperationException("第三方许可资源缺失");
        using var memory = new MemoryStream();
        resource.CopyTo(memory);
        var bytes = memory.ToArray();
        if (!File.Exists(targetPath) || !File.ReadAllBytes(targetPath).AsSpan().SequenceEqual(bytes))
        {
            File.WriteAllBytes(targetPath, bytes);
        }
    }

    private static Process? FindMinecraftProcess()
    {
        var processes = Process.GetProcessesByName("java")
            .Concat(Process.GetProcessesByName("javaw"))
            .ToArray();
        Process? selected = null;
        foreach (var process in processes)
        {
            try
            {
                if (!process.MainWindowTitle.Contains("Minecraft", StringComparison.OrdinalIgnoreCase))
                {
                    process.Dispose();
                    continue;
                }
                if (selected is null || process.StartTime > selected.StartTime)
                {
                    selected?.Dispose();
                    selected = process;
                }
                else
                {
                    process.Dispose();
                }
            }
            catch (InvalidOperationException)
            {
                process.Dispose();
            }
        }
        return selected;
    }

    public void Dispose()
    {
    }
}
