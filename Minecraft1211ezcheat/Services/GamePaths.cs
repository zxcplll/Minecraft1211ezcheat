using System.IO;

namespace Minecraft1211ezcheat.Services;

public sealed class GamePaths
{
    public string RuntimeDirectory => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "Minecraft1211ezcheat");
    public string SettingsPath => Path.Combine(RuntimeDirectory, "settings.json");
    public string StatusPath => Path.Combine(RuntimeDirectory, "status-v2.json");

    public static GamePaths Locate() => new();
}
