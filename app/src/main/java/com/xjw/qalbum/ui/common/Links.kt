package com.xjw.qalbum.ui.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/** 用浏览器打开网址；没有能打开网页的应用时提示一下 */
fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        Toast.makeText(context, "没有可以打开网页的应用", Toast.LENGTH_SHORT).show()
    }
}

/** 显示用的版本号：去掉后面的提交号与构建类型（0.4.1.e445722-debug → 0.4.1） */
fun shortVersionName(versionName: String): String = versionName.split('.').take(3).joinToString(".").substringBefore('-')
