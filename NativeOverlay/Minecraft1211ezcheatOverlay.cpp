#include <windows.h>
#include <jni.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

#include "imgui.h"
#include "imgui_impl_opengl3.h"
#include "imgui_impl_win32.h"

extern IMGUI_IMPL_API LRESULT ImGui_ImplWin32_WndProcHandler(HWND, UINT, WPARAM, LPARAM);

namespace {
constexpr int kConfigCount = 22;
constexpr int kEntityStride = 19;
constexpr float kHiddenCoordinate = -90000.0F;

enum ConfigIndex {
    Revision = 0,
    Speed = 1,
    ReachEnabled = 2,
    ReachDistance = 3,
    FlightEnabled = 4,
    NoClipEnabled = 5,
    FallProtection = 6,
    OreEnabled = 7,
    OreType = 8,
    OreRadius = 9,
    EspEnabled = 10,
    EspPlayers = 11,
    EspHostile = 12,
    EspPassive = 13,
    EspOther = 14,
    EspDistance = 15,
    AimEnabled = 16,
    AimDistance = 17,
    Brightness = 18,
    MenuVisible = 19,
    CtrlDown = 20,
    TreasureEnabled = 21,
};

std::mutex g_configMutex;
std::array<double, kConfigCount> g_config{
    0.0, 1.0, 0.0, 8.0, 0.0, 0.0, 1.0, 0.0, 0.0, 32.0,
    0.0, 1.0, 1.0, 1.0, 0.0, 64.0, 0.0, 48.0, 0.0, 0.0, 0.0, 0.0
};
HWND g_window = nullptr;
WNDPROC g_originalWndProc = nullptr;
bool g_initialized = false;
bool g_win32Initialized = false;
bool g_openGlInitialized = false;
bool g_menuVisible = false;
bool g_menuMinimized = false;
ImFont* g_font = nullptr;

using GlfwGetWin32Window = HWND(__cdecl*)(void*);

int64_t nowMillis() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();
}

void markChanged(std::array<double, kConfigCount>& config) {
    const auto next = std::max<int64_t>(nowMillis(), static_cast<int64_t>(config[Revision]) + 1);
    config[Revision] = static_cast<double>(next);
}

bool isInputMessage(UINT message) {
    return (message >= WM_MOUSEFIRST && message <= WM_MOUSELAST)
        || (message >= WM_KEYFIRST && message <= WM_KEYLAST)
        || message == WM_INPUT || message == WM_CHAR;
}

bool isMouseMessage(UINT message) {
    return (message >= WM_MOUSEFIRST && message <= WM_MOUSELAST) || message == WM_INPUT;
}

bool isKeyboardMessage(UINT message) {
    return (message >= WM_KEYFIRST && message <= WM_KEYLAST) || message == WM_CHAR;
}

bool isInsertMessage(UINT message, WPARAM wParam) {
    switch (message) {
        case WM_KEYDOWN:
        case WM_KEYUP:
        case WM_SYSKEYDOWN:
        case WM_SYSKEYUP:
            return wParam == VK_INSERT;
        default:
            return false;
    }
}

LRESULT CALLBACK overlayWndProc(HWND window, UINT message, WPARAM wParam, LPARAM lParam) {
    const bool insertMessage = isInsertMessage(message, wParam);
    if (message == WM_KEYDOWN && wParam == VK_INSERT && (lParam & (1LL << 30)) == 0) {
        std::scoped_lock lock(g_configMutex);
        if (g_menuVisible) {
            g_menuVisible = false;
            g_menuMinimized = false;
        } else {
            g_menuVisible = true;
            g_menuMinimized = false;
        }
        g_config[MenuVisible] = g_menuVisible ? 1.0 : 0.0;
        markChanged(g_config);
    }

    if (g_initialized) {
        ImGui_ImplWin32_WndProcHandler(window, message, wParam, lParam);
        if ((g_menuVisible || g_menuMinimized) && isInputMessage(message) && !insertMessage) {
            const ImGuiIO& io = ImGui::GetIO();
            if ((isMouseMessage(message) && io.WantCaptureMouse)
                    || (isKeyboardMessage(message) && io.WantCaptureKeyboard)) {
                return 0;
            }
        }
    }
    return g_originalWndProc
        ? CallWindowProcW(g_originalWndProc, window, message, wParam, lParam)
        : DefWindowProcW(window, message, wParam, lParam);
}

HWND resolveWindow(jlong glfwWindow) {
    HMODULE glfw = GetModuleHandleW(L"glfw.dll");
    if (!glfw) {
        return nullptr;
    }
    auto getWindow = reinterpret_cast<GlfwGetWin32Window>(GetProcAddress(glfw, "glfwGetWin32Window"));
    return getWindow ? getWindow(reinterpret_cast<void*>(glfwWindow)) : nullptr;
}

void shutdownOverlay() {
    if (g_window && g_originalWndProc) {
        SetWindowLongPtrW(g_window, GWLP_WNDPROC, reinterpret_cast<LONG_PTR>(g_originalWndProc));
    }
    if (g_openGlInitialized) ImGui_ImplOpenGL3_Shutdown();
    if (g_win32Initialized) ImGui_ImplWin32_Shutdown();
    if (ImGui::GetCurrentContext()) ImGui::DestroyContext();
    g_initialized = false;
    g_win32Initialized = false;
    g_openGlInitialized = false;
    g_window = nullptr;
    g_originalWndProc = nullptr;
    g_font = nullptr;
    g_menuMinimized = false;
}

bool initializeOverlay(jlong glfwWindow) {
    HWND window = resolveWindow(glfwWindow);
    if (!window || !wglGetCurrentContext()) {
        return false;
    }
    if (g_initialized && g_window == window) {
        return true;
    }
    shutdownOverlay();
    g_window = window;

    IMGUI_CHECKVERSION();
    ImGui::CreateContext();
    ImGuiIO& io = ImGui::GetIO();
    io.IniFilename = nullptr;
    io.LogFilename = nullptr;
    io.ConfigFlags |= ImGuiConfigFlags_NavEnableKeyboard;

    const ImWchar* glyphs = io.Fonts->GetGlyphRangesChineseFull();
    g_font = io.Fonts->AddFontFromFileTTF("C:\\Windows\\Fonts\\msyh.ttc", 17.0F, nullptr, glyphs);
    if (!g_font) {
        g_font = io.Fonts->AddFontFromFileTTF("C:\\Windows\\Fonts\\simhei.ttf", 17.0F, nullptr, glyphs);
    }
    if (!g_font) {
        g_font = io.Fonts->AddFontDefault();
    }

    ImGuiStyle& style = ImGui::GetStyle();
    style.WindowRounding = 6.0F;
    style.FrameRounding = 4.0F;
    style.PopupRounding = 4.0F;
    style.GrabRounding = 3.0F;
    style.WindowPadding = ImVec2(16.0F, 14.0F);
    style.FramePadding = ImVec2(10.0F, 6.0F);
    style.ItemSpacing = ImVec2(10.0F, 9.0F);
    style.WindowBorderSize = 1.0F;
    auto& colors = style.Colors;
    colors[ImGuiCol_WindowBg] = ImVec4(0.075F, 0.085F, 0.082F, 0.97F);
    colors[ImGuiCol_Border] = ImVec4(0.24F, 0.28F, 0.27F, 1.0F);
    colors[ImGuiCol_FrameBg] = ImVec4(0.14F, 0.16F, 0.15F, 1.0F);
    colors[ImGuiCol_FrameBgHovered] = ImVec4(0.19F, 0.23F, 0.21F, 1.0F);
    colors[ImGuiCol_FrameBgActive] = ImVec4(0.08F, 0.43F, 0.36F, 1.0F);
    colors[ImGuiCol_Button] = ImVec4(0.06F, 0.48F, 0.39F, 1.0F);
    colors[ImGuiCol_ButtonHovered] = ImVec4(0.08F, 0.60F, 0.49F, 1.0F);
    colors[ImGuiCol_CheckMark] = ImVec4(0.38F, 0.92F, 0.73F, 1.0F);
    colors[ImGuiCol_SliderGrab] = ImVec4(0.20F, 0.78F, 0.61F, 1.0F);
    colors[ImGuiCol_Header] = ImVec4(0.10F, 0.41F, 0.35F, 1.0F);
    colors[ImGuiCol_HeaderHovered] = ImVec4(0.12F, 0.54F, 0.44F, 1.0F);
    colors[ImGuiCol_Tab] = ImVec4(0.13F, 0.15F, 0.14F, 1.0F);
    colors[ImGuiCol_TabActive] = ImVec4(0.07F, 0.43F, 0.35F, 1.0F);

    if (!ImGui_ImplWin32_InitForOpenGL(window)) {
        shutdownOverlay();
        return false;
    }
    g_win32Initialized = true;
    if (!ImGui_ImplOpenGL3_Init("#version 150")) {
        shutdownOverlay();
        return false;
    }
    g_openGlInitialized = true;
    SetLastError(0);
    auto previous = reinterpret_cast<WNDPROC>(SetWindowLongPtrW(
        window, GWLP_WNDPROC, reinterpret_cast<LONG_PTR>(overlayWndProc)));
    if (!previous && GetLastError() != 0) {
        shutdownOverlay();
        return false;
    }
    g_originalWndProc = previous;
    g_initialized = true;
    return true;
}

ImU32 categoryColor(int category, float alpha = 1.0F) {
    switch (category) {
        case 0: return ImGui::ColorConvertFloat4ToU32(ImVec4(0.22F, 0.78F, 1.0F, alpha));
        case 1: return ImGui::ColorConvertFloat4ToU32(ImVec4(1.0F, 0.30F, 0.27F, alpha));
        case 2: return ImGui::ColorConvertFloat4ToU32(ImVec4(0.35F, 0.94F, 0.49F, alpha));
        default: return ImGui::ColorConvertFloat4ToU32(ImVec4(1.0F, 0.78F, 0.24F, alpha));
    }
}

void drawEntities(const std::vector<float>& values, const std::vector<std::string>& labels) {
    static constexpr int edges[][2] = {
        {0,1},{1,3},{3,2},{2,0}, {4,5},{5,7},{7,6},{6,4},
        {0,4},{1,5},{2,6},{3,7}
    };
    ImDrawList* draw = ImGui::GetBackgroundDrawList();
    const int count = std::min<int>(static_cast<int>(values.size() / kEntityStride), static_cast<int>(labels.size()));
    for (int index = 0; index < count; ++index) {
        const float* entity = values.data() + index * kEntityStride;
        if (entity[0] <= kHiddenCoordinate) {
            continue;
        }
        const int category = static_cast<int>(entity[16]);
        const float distance = entity[17];
        const float alpha = std::clamp(1.15F - distance / 150.0F, 0.38F, 1.0F);
        const ImU32 color = categoryColor(category, alpha);
        const float thickness = std::clamp(2.8F - distance / 55.0F, 1.1F, 2.6F);
        float minX = entity[0], maxX = entity[0], minY = entity[1];
        for (int corner = 0; corner < 8; ++corner) {
            minX = std::min(minX, entity[corner * 2]);
            maxX = std::max(maxX, entity[corner * 2]);
            minY = std::min(minY, entity[corner * 2 + 1]);
        }
        for (const auto& edge : edges) {
            draw->AddLine(
                ImVec2(entity[edge[0] * 2], entity[edge[0] * 2 + 1]),
                ImVec2(entity[edge[1] * 2], entity[edge[1] * 2 + 1]), color, thickness);
        }
        const float fontSize = std::clamp(17.0F - distance / 16.0F, 11.0F, 16.0F);
        const ImVec2 textSize = g_font->CalcTextSizeA(fontSize, FLT_MAX, 0.0F, labels[index].c_str());
        const ImVec2 textPosition(std::max(4.0F, (minX + maxX - textSize.x) * 0.5F), std::max(3.0F, minY - textSize.y - 5.0F));
        draw->AddRectFilled(
            ImVec2(textPosition.x - 4.0F, textPosition.y - 2.0F),
            ImVec2(textPosition.x + textSize.x + 4.0F, textPosition.y + textSize.y + 2.0F),
            IM_COL32(10, 13, 12, 178), 3.0F);
        draw->AddText(g_font, fontSize, textPosition, IM_COL32(245, 249, 247, 245), labels[index].c_str());
    }
}

void drawBrightnessOverlay(double brightness) {
    const float level = std::clamp(static_cast<float>(brightness), 0.0F, 1.0F);
    if (level <= 0.001F) return;

    // Iris shader packs can bypass Options.gamma. A restrained white lift keeps the
    // game visible at night without touching shader-pack files or the game's UI state.
    const float alpha = std::clamp(std::pow(level, 0.68F) * 0.86F, 0.0F, 0.86F);
    const ImVec2 display = ImGui::GetIO().DisplaySize;
    ImGui::GetBackgroundDrawList()->AddRectFilled(
        ImVec2(0.0F, 0.0F), display,
        IM_COL32(255, 255, 255, static_cast<int>(std::round(alpha * 255.0F))));
}

struct Point3 { float x, y, z; };

ImVec2 projectArrow(const Point3& point, const ImVec2& center) {
    const float depth = std::max(45.0F, 180.0F + point.z);
    const float scale = 155.0F / depth;
    return ImVec2(center.x + point.x * scale, center.y - point.y * scale);
}

void drawOreArrow(float relativeYaw, float relativePitch, float distance, const std::string& name) {
    ImDrawList* draw = ImGui::GetBackgroundDrawList();
    const ImVec2 center(ImGui::GetIO().DisplaySize.x * 0.5F, 92.0F);
    const float cp = std::cos(relativePitch);
    Point3 direction{std::sin(relativeYaw) * cp, -std::sin(relativePitch), std::cos(relativeYaw) * cp};
    const float length = std::sqrt(direction.x * direction.x + direction.y * direction.y + direction.z * direction.z);
    if (length < 0.001F) return;
    direction = {direction.x / length, direction.y / length, direction.z / length};
    Point3 reference = std::abs(direction.y) < 0.85F ? Point3{0, 1, 0} : Point3{1, 0, 0};
    Point3 side{
        direction.y * reference.z - direction.z * reference.y,
        direction.z * reference.x - direction.x * reference.z,
        direction.x * reference.y - direction.y * reference.x};
    const float sideLength = std::sqrt(side.x * side.x + side.y * side.y + side.z * side.z);
    side = {side.x / sideLength, side.y / sideLength, side.z / sideLength};
    Point3 up{
        side.y * direction.z - side.z * direction.y,
        side.z * direction.x - side.x * direction.z,
        side.x * direction.y - side.y * direction.x};
    const float spin = static_cast<float>(ImGui::GetTime() * 1.7);
    const float cs = std::cos(spin), sn = std::sin(spin);
    Point3 ringA{side.x * cs + up.x * sn, side.y * cs + up.y * sn, side.z * cs + up.z * sn};
    Point3 ringB{-side.x * sn + up.x * cs, -side.y * sn + up.y * cs, -side.z * sn + up.z * cs};
    auto combine = [](Point3 a, float as, Point3 b, float bs, Point3 c, float cs2) {
        return Point3{a.x * as + b.x * bs + c.x * cs2, a.y * as + b.y * bs + c.y * cs2, a.z * as + b.z * bs + c.z * cs2};
    };
    Point3 tail = combine(direction, -38.0F, side, 0, up, 0);
    Point3 neck = combine(direction, 18.0F, side, 0, up, 0);
    Point3 tip = combine(direction, 58.0F, side, 0, up, 0);
    std::array<Point3, 4> ring{
        combine(direction, 18.0F, ringA, 14.0F, ringB, 0),
        combine(direction, 18.0F, ringA, 0, ringB, 14.0F),
        combine(direction, 18.0F, ringA, -14.0F, ringB, 0),
        combine(direction, 18.0F, ringA, 0, ringB, -14.0F)};
    const ImU32 gold = IM_COL32(255, 202, 70, 245);
    const ImU32 glow = IM_COL32(255, 190, 55, 65);
    draw->AddCircleFilled(center, 38.0F, IM_COL32(10, 14, 13, 125), 40);
    draw->AddCircle(center, 38.0F, glow, 40, 2.0F);
    draw->AddLine(projectArrow(tail, center), projectArrow(neck, center), glow, 7.0F);
    draw->AddLine(projectArrow(tail, center), projectArrow(neck, center), gold, 3.0F);
    for (int i = 0; i < 4; ++i) {
        draw->AddLine(projectArrow(ring[i], center), projectArrow(ring[(i + 1) % 4], center), gold, 2.0F);
        draw->AddLine(projectArrow(ring[i], center), projectArrow(tip, center), gold, 2.2F);
    }
    const std::string text = name + "  " + std::to_string(static_cast<int>(std::round(distance))) + " m";
    const ImVec2 size = g_font->CalcTextSizeA(15.0F, FLT_MAX, 0, text.c_str());
    const ImVec2 position(center.x - size.x * 0.5F, 137.0F);
    draw->AddRectFilled(
        ImVec2(position.x - 6.0F, position.y - 3.0F),
        ImVec2(position.x + size.x + 6.0F, position.y + size.y + 3.0F),
        IM_COL32(10, 13, 12, 190), 3.0F);
    draw->AddText(g_font, 15.0F, position, IM_COL32(255, 225, 134, 255), text.c_str());
}

bool checkbox(const char* label, double& value) {
    bool checked = value > 0.5;
    if (!ImGui::Checkbox(label, &checked)) return false;
    value = checked ? 1.0 : 0.0;
    return true;
}

void drawMenu(std::array<double, kConfigCount>& config) {
    if (!g_menuVisible) return;
    bool changed = false;
    ImGui::SetNextWindowSize(ImVec2(535.0F, 435.0F), ImGuiCond_FirstUseEver);
    ImGui::SetNextWindowPos(ImVec2(42.0F, 58.0F), ImGuiCond_FirstUseEver);
    ImGui::Begin("Minecraft1211ezcheat", nullptr, ImGuiWindowFlags_NoCollapse);
    ImGui::TextColored(ImVec4(0.38F, 0.92F, 0.73F, 1.0F), "Injected");
    ImGui::SameLine(ImGui::GetWindowContentRegionMax().x - ImGui::GetFrameHeight());
    if (ImGui::SmallButton("-")) {
        g_menuMinimized = true;
        g_menuVisible = false;
        config[MenuVisible] = 0.0;
        changed = true;
    }
    if (ImGui::IsItemHovered()) ImGui::SetTooltip("Minimize");

    if (!g_menuMinimized && ImGui::BeginTabBar("TrainerTabs")) {
        if (ImGui::BeginTabItem("移动")) {
            float speed = static_cast<float>(config[Speed]);
            changed |= ImGui::SliderFloat("移动倍率", &speed, 0.5F, 10.0F, "%.2fx");
            config[Speed] = speed;
            changed |= checkbox("交互距离", config[ReachEnabled]);
            if (config[ReachEnabled] > 0.5) {
                float reach = static_cast<float>(config[ReachDistance]);
                changed |= ImGui::SliderFloat("距离##reach", &reach, 3.0F, 32.0F, "%.1f m");
                config[ReachDistance] = reach;
            }
            changed |= checkbox("允许飞行（双击空格开关）", config[FlightEnabled]);
            changed |= checkbox("无视碰撞体积", config[NoClipEnabled]);
            changed |= checkbox("免疫落地伤害", config[FallProtection]);
            if (config[NoClipEnabled] > 0.5) config[FlightEnabled] = 1.0;
            ImGui::EndTabItem();
        }
        if (ImGui::BeginTabItem("矿物追踪")) {
            changed |= checkbox("启用最近矿物指向", config[OreEnabled]);
            static const char* ores[] = {
                "钻石矿", "金矿", "铁矿", "绿宝石矿", "红石矿",
                "青金石矿", "煤矿", "铜矿", "下界石英矿", "远古残骸"
            };
            int ore = std::clamp(static_cast<int>(config[OreType]), 0, 9);
            changed |= ImGui::Combo("矿物", &ore, ores, IM_ARRAYSIZE(ores));
            config[OreType] = ore;
            int radius = std::clamp(static_cast<int>(config[OreRadius]), 8, 96);
            changed |= ImGui::SliderInt("扫描半径", &radius, 8, 96, "%d m");
            config[OreRadius] = radius;
            changed |= checkbox("寻宝：箱子", config[TreasureEnabled]);
            ImGui::EndTabItem();
        }
        if (ImGui::BeginTabItem("实体透视")) {
            changed |= checkbox("启用 3D 碰撞箱", config[EspEnabled]);
            changed |= checkbox("玩家", config[EspPlayers]); ImGui::SameLine();
            changed |= checkbox("敌对", config[EspHostile]); ImGui::SameLine();
            changed |= checkbox("被动", config[EspPassive]); ImGui::SameLine();
            changed |= checkbox("其他", config[EspOther]);
            int distance = std::clamp(static_cast<int>(config[EspDistance]), 8, 512);
            changed |= ImGui::SliderInt("显示距离", &distance, 8, 512, "%d m");
            config[EspDistance] = distance;
            ImGui::EndTabItem();
        }
        if (ImGui::BeginTabItem("辅助瞄准")) {
            changed |= checkbox("按住 Ctrl 锁定最近实体头部", config[AimEnabled]);
            int distance = std::clamp(static_cast<int>(config[AimDistance]), 4, 256);
            changed |= ImGui::SliderInt("锁定距离", &distance, 4, 256, "%d m");
            config[AimDistance] = distance;
            const bool ctrl = (GetAsyncKeyState(VK_CONTROL) & 0x8000) != 0;
            ImGui::TextColored(ctrl ? ImVec4(0.35F, 0.95F, 0.55F, 1) : ImVec4(0.65F, 0.68F, 0.67F, 1),
                ctrl ? "Ctrl：锁定中" : "Ctrl：未按下");
            ImGui::EndTabItem();
        }
        if (ImGui::BeginTabItem("视觉")) {
            int brightness = std::clamp(static_cast<int>(std::round(config[Brightness] * 100.0)), 0, 100);
            changed |= ImGui::SliderInt("环境亮度", &brightness, 0, 100, "%d%%");
            config[Brightness] = brightness / 100.0;
            ImGui::EndTabItem();
        }
        ImGui::EndTabBar();
    }
    ImGui::End();
    if (changed) markChanged(config);
}

void drawStatusBar(std::array<double, kConfigCount>& config) {
    if (!g_menuMinimized || g_menuVisible) return;

    ImGui::SetNextWindowPos(ImVec2(42.0F, 58.0F), ImGuiCond_Always);
    ImGui::SetNextWindowBgAlpha(0.92F);
    constexpr ImGuiWindowFlags kStatusFlags =
        ImGuiWindowFlags_NoTitleBar
        | ImGuiWindowFlags_NoResize
        | ImGuiWindowFlags_NoMove
        | ImGuiWindowFlags_NoCollapse
        | ImGuiWindowFlags_AlwaysAutoResize
        | ImGuiWindowFlags_NoSavedSettings
        | ImGuiWindowFlags_NoFocusOnAppearing;
    ImGui::Begin("Minecraft1211ezcheat##status", nullptr, kStatusFlags);
    ImGui::TextColored(ImVec4(0.38F, 0.92F, 0.73F, 1.0F), "Minecraft1211ezcheat  |  Injected");
    ImGui::SameLine();
    if (ImGui::SmallButton("+")) {
        g_menuMinimized = false;
        g_menuVisible = true;
        config[MenuVisible] = 1.0;
        markChanged(config);
    }
    if (ImGui::IsItemHovered()) ImGui::SetTooltip("Open menu");
    ImGui::End();
}

std::string fromJavaString(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_zxcplll_minecraft1211ezcheat_NativeOverlayBridgeV6_configure(
    JNIEnv* env, jclass, jdoubleArray values) {
    if (!values || env->GetArrayLength(values) < kConfigCount) return;
    std::array<double, kConfigCount> incoming{};
    env->GetDoubleArrayRegion(values, 0, kConfigCount, incoming.data());
    std::scoped_lock lock(g_configMutex);
    if (incoming[Revision] >= g_config[Revision]) {
        const double menu = g_menuVisible ? 1.0 : 0.0;
        const double ctrl = g_config[CtrlDown];
        g_config = incoming;
        g_config[MenuVisible] = menu;
        g_config[CtrlDown] = ctrl;
    }
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_io_github_zxcplll_minecraft1211ezcheat_NativeOverlayBridgeV6_snapshot(JNIEnv* env, jclass) {
    std::array<double, kConfigCount> copy{};
    {
        std::scoped_lock lock(g_configMutex);
        g_config[MenuVisible] = g_menuVisible ? 1.0 : 0.0;
        g_config[CtrlDown] = (GetAsyncKeyState(VK_CONTROL) & 0x8000) != 0 ? 1.0 : 0.0;
        copy = g_config;
    }
    jdoubleArray result = env->NewDoubleArray(kConfigCount);
    if (result) env->SetDoubleArrayRegion(result, 0, kConfigCount, copy.data());
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_zxcplll_minecraft1211ezcheat_NativeOverlayBridgeV6_render(
    JNIEnv* env,
    jclass,
    jlong glfwWindow,
    jfloatArray entityValues,
    jobjectArray entityLabels,
    jfloat oreYaw,
    jfloat orePitch,
    jfloat oreDistance,
    jstring oreName) {
    if (!initializeOverlay(glfwWindow)) return;

    std::vector<float> values;
    if (entityValues) {
        const jsize length = env->GetArrayLength(entityValues);
        values.resize(length);
        env->GetFloatArrayRegion(entityValues, 0, length, values.data());
    }
    std::vector<std::string> labels;
    if (entityLabels) {
        const jsize count = env->GetArrayLength(entityLabels);
        labels.reserve(count);
        for (jsize i = 0; i < count; ++i) {
            auto text = static_cast<jstring>(env->GetObjectArrayElement(entityLabels, i));
            labels.push_back(fromJavaString(env, text));
            if (text) env->DeleteLocalRef(text);
        }
    }
    const std::string oreLabel = fromJavaString(env, oreName);

    std::array<double, kConfigCount> config{};
    {
        std::scoped_lock lock(g_configMutex);
        g_config[CtrlDown] = (GetAsyncKeyState(VK_CONTROL) & 0x8000) != 0 ? 1.0 : 0.0;
        config = g_config;
    }

    ImGui_ImplOpenGL3_NewFrame();
    ImGui_ImplWin32_NewFrame();
    ImGui::NewFrame();
    if (g_font) ImGui::PushFont(g_font);
    drawBrightnessOverlay(config[Brightness]);
    if (config[EspEnabled] > 0.5 || config[TreasureEnabled] > 0.5) drawEntities(values, labels);
    if (config[OreEnabled] > 0.5 && oreDistance >= 0.0F && !oreLabel.empty()) {
        drawOreArrow(oreYaw, orePitch, oreDistance, oreLabel);
    }
    drawMenu(config);
    drawStatusBar(config);
    if (g_font) ImGui::PopFont();
    ImGui::Render();
    ImGui_ImplOpenGL3_RenderDrawData(ImGui::GetDrawData());

    {
        std::scoped_lock lock(g_configMutex);
        if (config[Revision] >= g_config[Revision]) {
            config[MenuVisible] = g_menuVisible ? 1.0 : 0.0;
            config[CtrlDown] = (GetAsyncKeyState(VK_CONTROL) & 0x8000) != 0 ? 1.0 : 0.0;
            g_config = config;
        }
    }
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_zxcplll_minecraft1211ezcheat_NativeOverlayBridgeV6_shutdown(JNIEnv*, jclass) {
    shutdownOverlay();
}

BOOL APIENTRY DllMain(HMODULE, DWORD, LPVOID) {
    return TRUE;
}
