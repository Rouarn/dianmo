package com.woyuxiaji.dianmo

import android.app.Application
import com.topjohnwu.superuser.Shell

class DianMoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // libsu 的 root shell 配置：把 stderr 也重定向进输出，方便调试
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(10)
        )
    }
}
