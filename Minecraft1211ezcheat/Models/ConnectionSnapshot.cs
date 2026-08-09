namespace Minecraft1211ezcheat.Models;

public enum ConnectionState
{
    Offline,
    Starting,
    RestartRequired,
    Connected,
    Error
}

public sealed record ConnectionSnapshot(
    ConnectionState State,
    string Summary,
    string Detail,
    string? PlayerName = null);
