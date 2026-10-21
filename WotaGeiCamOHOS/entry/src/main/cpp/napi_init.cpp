/*
 * GL-0 原生模块（对齐上游 38d712a 的移植前置）：
 * - napi 模块名 nm_modname 必须与 ArkTS 侧 XComponent({ libraryname: 'entry' }) 及
 *   CMake 目标名一致（三者统一为 entry，产物 libentry.so）；
 * - Init：从 exports 的 OH_NATIVE_XCOMPONENT_OBJ 取 OH_NativeXComponent*，注册
 *   surface 回调（Created/Changed/Destroyed）与 OnFrame 帧回调（API 11+，vsync 驱动）；
 * - 全部打点走 hilog tag WotaGL / domain 0x0000。
 */
#include <napi/native_api.h>
#include <ace/xcomponent/native_interface_xcomponent.h>
#include <hilog/log.h>

#include "gl_render.h"

#define WOTA_LOG_DOMAIN 0x0000
#define WOTA_LOG_TAG "WotaGL"

static void OnSurfaceCreatedCB(OH_NativeXComponent* component, void* window)
{
    uint64_t width = 0;
    uint64_t height = 0;
    OH_NativeXComponent_GetXComponentSize(component, window, &width, &height);
    bool ok = WotaGlInit(window, width, height);
    OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
        "surface created %{public}llux%{public}llu gl=%{public}d",
        static_cast<unsigned long long>(width), static_cast<unsigned long long>(height), ok ? 1 : 0);
}

static void OnSurfaceChangedCB(OH_NativeXComponent* component, void* window)
{
    uint64_t width = 0;
    uint64_t height = 0;
    OH_NativeXComponent_GetXComponentSize(component, window, &width, &height);
    WotaGlResize(width, height);
    OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
        "surface changed %{public}llux%{public}llu",
        static_cast<unsigned long long>(width), static_cast<unsigned long long>(height));
}

static void OnSurfaceDestroyedCB(OH_NativeXComponent* component, void* window)
{
    (void)component;
    (void)window;
    WotaGlDestroy();
    OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG, "surface destroyed");
}

static void OnFrameCB(OH_NativeXComponent* component, uint64_t timestamp, uint64_t targetTimestamp)
{
    (void)component;
    (void)targetTimestamp;
    WotaGlRender(timestamp);
}

static OH_NativeXComponent_Callback g_surfaceCallback;

static napi_value Init(napi_env env, napi_value exports)
{
    OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG, "module init");
    napi_value exportInstance = nullptr;
    if (napi_get_named_property(env, exports, OH_NATIVE_XCOMPONENT_OBJ, &exportInstance) != napi_ok) {
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG,
            "get XComponent obj failed (page not bound?)");
        return exports;
    }
    OH_NativeXComponent* component = nullptr;
    if (napi_unwrap(env, exportInstance, reinterpret_cast<void**>(&component)) != napi_ok ||
        component == nullptr) {
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG, "unwrap XComponent failed");
        return exports;
    }
    g_surfaceCallback.OnSurfaceCreated = OnSurfaceCreatedCB;
    g_surfaceCallback.OnSurfaceChanged = OnSurfaceChangedCB;
    g_surfaceCallback.OnSurfaceDestroyed = OnSurfaceDestroyedCB;
    g_surfaceCallback.DispatchTouchEvent = nullptr;
    if (OH_NativeXComponent_RegisterCallback(component, &g_surfaceCallback) != 0) {
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG, "register surface callback failed");
    }
    if (OH_NativeXComponent_RegisterOnFrameCallback(component, OnFrameCB) != 0) {
        OH_LOG_Print(LOG_APP, LOG_ERROR, WOTA_LOG_DOMAIN, WOTA_LOG_TAG, "register frame callback failed");
    }
    OH_LOG_Print(LOG_APP, LOG_INFO, WOTA_LOG_DOMAIN, WOTA_LOG_TAG, "xcomponent callbacks registered");
    return exports;
}

static napi_module g_entryModule = {
    .nm_version = 1,
    .nm_flags = 0,
    .nm_filename = nullptr,
    .nm_register_func = Init,
    .nm_modname = "entry",
    .nm_priv = nullptr,
    .reserved = {0},
};

extern "C" __attribute__((constructor)) void RegisterEntryModule(void)
{
    napi_module_register(&g_entryModule);
}
