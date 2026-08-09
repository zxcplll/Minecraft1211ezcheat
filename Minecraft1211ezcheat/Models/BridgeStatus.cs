namespace Minecraft1211ezcheat.Models;

public sealed record BridgeStatus
{
    public bool Active { get; init; }
    public bool PlayerReady { get; init; }
    public bool Singleplayer { get; init; }
    public long ProcessId { get; init; }
    public long SettingsRevision { get; init; }
    public string[] PlayerNames { get; init; } = [];
    public string HeartbeatUtc { get; init; } = string.Empty;
    public string Version { get; init; } = string.Empty;
    public string Error { get; init; } = string.Empty;

    public bool HasFreshHeartbeat(TimeSpan maximumAge)
    {
        if (!DateTimeOffset.TryParse(HeartbeatUtc, out var heartbeat))
        {
            return false;
        }

        var age = DateTimeOffset.UtcNow - heartbeat;
        return age >= TimeSpan.Zero && age <= maximumAge;
    }
}
