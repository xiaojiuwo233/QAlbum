// 扫描专用的特权文件操作接口。
//
// 同一份实现（FileOpsImpl）跑在两种宿主进程里：
//   - Root：libsu RootService（uid 0）
//   - Shizuku：UserService（uid 2000，与现有 shell 命令同权限）
//
// 设计约束：
//   - 单次 binder 事务 < 1MB，所以列举接口都带 offset/limit 分页；
//     返回值用紧凑的打包字符串（相对名而非绝对路径），由客户端拼回完整路径。
//   - 文件头批量读取用「4 字节长度 + 负载」的拼接 byte[]（HeaderBatchCodec），
//     避免 List<byte[]> 不被 AIDL 支持的问题。
package com.xjw.qalbum.fileops;

interface IFileOps {
    // Shizuku UserService 约定的销毁事务号（Shizuku 服务端通过它通知退出进程）
    void destroy() = 16777114;

    // 列出 root 直接子目录。每项 "mtimeMs|name"。
    // 分页基于原始 readdir 序（排序后），结果末尾追加 "+" 表示还有下一页。
    String[] listSubdirs(String root, int offset, int limit) = 1;

    // 列出 dir 下深度 1 的普通文件。每项 "size|mtimeMs|name"，分页约定同上。
    String[] listFiles(String dir, int offset, int limit) = 2;

    // 批量读取每个文件的前 n 字节。返回 HeaderBatchCodec 打包格式：
    // 每项 = 4 字节大端长度（-1 表示不可读）+ 实际字节。
    byte[] readHeaders(in String[] paths, int n) = 3;
}
