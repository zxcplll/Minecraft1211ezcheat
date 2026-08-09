using System.IO;
using System.Windows;
using System.Windows.Media;
using System.Windows.Threading;
using Minecraft1211ezcheat.Models;
using Minecraft1211ezcheat.Services;

namespace Minecraft1211ezcheat;

public partial class MainWindow : Window
{
    private readonly GamePaths paths = null!;
    private readonly TrainerConfigService configService = null!;
    private readonly GameConnectionService connectionService = null!;
    private readonly DispatcherTimer connectionTimer = null!;
    private readonly object settingsLock = new();
    private CancellationTokenSource? heartbeatCancellation;
    private Task? heartbeatTask;
    private TrainerSettings settings = TrainerSettings.Default;
    private bool checkingConnection;

    public MainWindow()
    {
        InitializeComponent();

        paths = GamePaths.Locate();
        configService = new TrainerConfigService(paths);
        connectionService = new GameConnectionService(paths, configService);
        connectionTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(2) };
        connectionTimer.Tick += ConnectionTimerTick;
        Loaded += WindowLoaded;
        Closed += WindowClosed;
    }

    private async void WindowLoaded(object sender, RoutedEventArgs e)
    {
        lock (settingsLock)
        {
            settings = configService.Load() with
            {
                SessionActive = true,
                UpdatedAtUtc = DateTimeOffset.UtcNow.ToString("O")
            };
            configService.Save(settings);
        }

        HeartbeatText.Text = "注入租约：运行中";
        connectionTimer.Start();
        heartbeatCancellation = new CancellationTokenSource();
        heartbeatTask = RunHeartbeatAsync(heartbeatCancellation.Token);
        await RefreshConnectionAsync();
    }

    private async void ConnectionTimerTick(object? sender, EventArgs e) => await RefreshConnectionAsync();

    private async Task RefreshConnectionAsync()
    {
        if (checkingConnection)
        {
            return;
        }

        checkingConnection = true;
        try
        {
            var snapshot = await connectionService.CheckAsync();
            var summary = snapshot.PlayerName is { Length: > 0 }
                ? $"{snapshot.Summary} · {snapshot.PlayerName}"
                : snapshot.Summary;
            ConnectionText.Text = $"注入状态：{summary}";
            ConnectionDetailText.Text = snapshot.Detail;

            var colorKey = snapshot.State switch
            {
                ConnectionState.Connected => "AccentBrush",
                ConnectionState.RestartRequired or ConnectionState.Starting => "WarningBrush",
                ConnectionState.Error => "WarningBrush",
                _ => "OfflineBrush"
            };
            ConnectionDot.Fill = (Brush)FindResource(colorKey);
            HeartbeatText.Text = snapshot.State == ConnectionState.Connected
                ? "注入租约：保持中"
                : "注入租约：等待附加";
        }
        finally
        {
            checkingConnection = false;
        }
    }

    private async Task RunHeartbeatAsync(CancellationToken cancellationToken)
    {
        using var timer = new PeriodicTimer(TimeSpan.FromSeconds(1));
        try
        {
            while (await timer.WaitForNextTickAsync(cancellationToken).ConfigureAwait(false))
            {
                try
                {
                    lock (settingsLock)
                    {
                        var diskSettings = configService.Load();
                        if (diskSettings.Revision > settings.Revision)
                        {
                            settings = diskSettings;
                        }

                        settings = settings with
                        {
                            SessionActive = true,
                            UpdatedAtUtc = DateTimeOffset.UtcNow.ToString("O")
                        };
                        configService.Save(settings);
                    }
                }
                catch (Exception exception) when (exception is IOException or UnauthorizedAccessException)
                {
                    // Keep the lease alive; the next tick retries the atomic save.
                }
            }
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
        }
    }

    private void WindowClosed(object? sender, EventArgs e)
    {
        connectionTimer.Stop();
        heartbeatCancellation?.Cancel();
        try
        {
            heartbeatTask?.GetAwaiter().GetResult();
        }
        catch (OperationCanceledException)
        {
        }

        heartbeatCancellation?.Dispose();
        lock (settingsLock)
        {
            try
            {
                var diskSettings = configService.Load();
                if (diskSettings.Revision > settings.Revision)
                {
                    settings = diskSettings;
                }
            }
            catch (Exception exception) when (exception is IOException or UnauthorizedAccessException)
            {
            }

            settings = settings with
            {
                SessionActive = false,
                UpdatedAtUtc = DateTimeOffset.UtcNow.ToString("O")
            };
            configService.Save(settings);
        }

        connectionService.Dispose();
    }
}
