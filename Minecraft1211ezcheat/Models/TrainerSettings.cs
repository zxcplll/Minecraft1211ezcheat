namespace Minecraft1211ezcheat.Models;

public sealed record TrainerSettings
{
    public int SchemaVersion { get; init; } = 4;
    public long Revision { get; init; }
    public double SpeedMultiplier { get; init; } = 1.0;
    public bool ReachEnabled { get; init; }
    public double InteractionDistance { get; init; } = 8.0;
    public bool FlightEnabled { get; init; }
    public bool NoClipEnabled { get; init; }
    public bool FallProtectionEnabled { get; init; } = true;
    public bool OreTrackingEnabled { get; init; }
    public int OreType { get; init; }
    public int OreScanRadius { get; init; } = 32;
    public bool EntityEspEnabled { get; init; }
    public bool EspPlayers { get; init; } = true;
    public bool EspHostile { get; init; } = true;
    public bool EspPassive { get; init; } = true;
    public bool EspOther { get; init; }
    public int EntityEspDistance { get; init; } = 64;
    public bool AimAssistEnabled { get; init; }
    public int AimAssistDistance { get; init; } = 48;
    public double BrightnessLevel { get; init; }
    public bool SessionActive { get; init; } = true;
    public string UpdatedAtUtc { get; init; } = DateTimeOffset.UtcNow.ToString("O");

    public static TrainerSettings Default => new();

    public TrainerSettings Sanitize() => this with
    {
        SchemaVersion = 4,
        SpeedMultiplier = Math.Round(Math.Clamp(SpeedMultiplier, 0.5, 10.0), 2),
        InteractionDistance = Math.Round(Math.Clamp(InteractionDistance, 3.0, 32.0), 1),
        FlightEnabled = FlightEnabled || NoClipEnabled,
        OreType = Math.Clamp(OreType, 0, 9),
        OreScanRadius = Math.Clamp(OreScanRadius, 8, 96),
        EntityEspDistance = Math.Clamp(EntityEspDistance, 8, 512),
        AimAssistDistance = Math.Clamp(AimAssistDistance, 4, 256),
        BrightnessLevel = Math.Round(Math.Clamp(BrightnessLevel, 0.0, 1.0), 2)
    };
}
