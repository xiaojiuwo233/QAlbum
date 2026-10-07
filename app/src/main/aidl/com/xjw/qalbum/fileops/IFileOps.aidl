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
    // 分页基于排序后的目录项，结果末尾追加 "+" 表示还有下一页。
    // 同一目录从 offset==0 起的连续翻页基于服务端同一份快照（目录并发增删
    // 不会造成窗口漂移导致的漏项/重项），换目录或重新从 0 翻页即刷新快照。
    String[] listSubdirs(String root, int offset, int limit) = 1;

    // 列出 dir 下深度 1 的普通文件。每项 "size|mtimeMs|name"，分页约定同上。
    String[] listFiles(String dir, int offset, int limit) = 2;

    // 批量读取每个文件的前 n 字节。返回 HeaderBatchCodec 打包格式：
    // 每项 = 4 字节大端长度（-1 表示不可读）+ 实际字节。
    byte[] readHeaders(in String[] paths, int n) = 3;

    // 批量删除：在特权进程内对每个路径做原生 unlink，返回与 paths 等长、
    // 顺序对应的成功标志（已删或本就不存在=true）。清理走这里，避免逐文件起
    // shell 进程（4w 张时那是 45min 级别的开销）。一次事务的 paths 数由调用方
    // 分块控制在 binder 1MB 限制内。
    boolean[] deleteFiles(in String[] paths) = 4;

    // 在特权进程内只读打开文件，把 fd 经 binder 传回客户端直读（ParcelFileDescriptor）。
    // 分析期读整图走这里：每图只是一次 binder 往返 + fd 直读，替代「每图派生一个 cat
    // 进程 / libsu 逐文件往返」的旧路径（20w 图小时级 → 分钟级）。
    // 文件不可读 / 不存在返回 null；不受 binder 1MB 事务上限约束（只传 fd 句柄）。
    @nullable ParcelFileDescriptor openRead(String path) = 5;

    // QQ 来源只读接口，仅 Root 实现；限定三个必要数据库及 WAL，不接受任意目标路径。
    String qqAccountCatalog(int userId) = 6;
    String qqDatabaseState(int userId, String account) = 7;
    ParcelFileDescriptor openQqDatabaseRead(int userId, String account, String name) = 8;

    // 精确文件状态：S|size|mtimeMs|stamp，M=不存在，E=无法核查，O=非普通文件。
    String[] statFiles(in String[] paths) = 9;
    // 同一特权调用内核验状态后移动/删除。状态变化、符号链接、已有目标均拒绝。
    boolean[] moveFilesChecked(in String[] sources, in String[] targets, in String[] stamps) = 10;
    boolean[] deleteFilesChecked(in String[] paths, in String[] stamps) = 11;
}
