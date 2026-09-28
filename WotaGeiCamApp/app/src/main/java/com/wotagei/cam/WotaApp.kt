package com.wotagei.cam

import android.app.Application

/**
 * 启动期不做重活：Room / 媒体库查询全部延迟到进入对应页面，保证冷启动。
 */
class WotaApp : Application()
