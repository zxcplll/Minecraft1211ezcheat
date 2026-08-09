using System.IO;
using System.Text.Json;
using Minecraft1211ezcheat.Models;

namespace Minecraft1211ezcheat.Services;

public sealed class TrainerConfigService(GamePaths paths)
{
    private readonly object saveLock = new();

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = true,
        WriteIndented = true
    };

    public TrainerSettings Load()
    {
        Directory.CreateDirectory(paths.RuntimeDirectory);
        if (!File.Exists(paths.SettingsPath))
        {
            var defaults = TrainerSettings.Default with
            {
                Revision = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()
            };
            Save(defaults);
            return defaults;
        }

        try
        {
            var json = File.ReadAllText(paths.SettingsPath);
            return (JsonSerializer.Deserialize<TrainerSettings>(json, JsonOptions) ?? TrainerSettings.Default).Sanitize();
        }
        catch (JsonException)
        {
            var defaults = TrainerSettings.Default with
            {
                Revision = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()
            };
            Save(defaults);
            return defaults;
        }
    }

    public void Save(TrainerSettings settings)
    {
        lock (saveLock)
        {
            Directory.CreateDirectory(paths.RuntimeDirectory);
            var sanitized = settings.Sanitize();
            var tempPath = Path.Combine(paths.RuntimeDirectory, $"settings.{Guid.NewGuid():N}.tmp");
            var json = JsonSerializer.Serialize(sanitized, JsonOptions);
            File.WriteAllText(tempPath, json);
            try
            {
                for (var attempt = 0; ; attempt++)
                {
                    try
                    {
                        File.Move(tempPath, paths.SettingsPath, true);
                        return;
                    }
                    catch (IOException) when (attempt < 5)
                    {
                        Thread.Sleep(20 * (attempt + 1));
                    }
                }
            }
            finally
            {
                try
                {
                    File.Delete(tempPath);
                }
                catch (Exception exception) when (exception is IOException or UnauthorizedAccessException)
                {
                    // A later startup cleanup can remove a temp file still held by another process.
                }
            }
        }
    }

    public BridgeStatus? ReadBridgeStatus() => ReadBridgeStatus(paths.StatusPath);

    private static BridgeStatus? ReadBridgeStatus(string statusPath)
    {
        if (!File.Exists(statusPath))
        {
            return null;
        }

        try
        {
            var json = File.ReadAllText(statusPath);
            return JsonSerializer.Deserialize<BridgeStatus>(json, JsonOptions);
        }
        catch (IOException)
        {
            return null;
        }
        catch (JsonException)
        {
            return null;
        }
    }
}
