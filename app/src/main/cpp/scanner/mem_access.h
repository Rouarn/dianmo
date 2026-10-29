#pragma once

#include <sys/types.h>

#include <cstddef>
#include <cstdint>

/// 封装对目标进程内存的读写。
/// 首选 process_vm_readv/writev（无需 attach），失败时退回 /proc/<pid>/mem。
class MemoryAccess {
public:
    MemoryAccess() = default;
    ~MemoryAccess();

    MemoryAccess(const MemoryAccess&) = delete;
    MemoryAccess& operator=(const MemoryAccess&) = delete;

    /// 打开目标进程；返回是否成功。
    bool open(pid_t pid);

    void close();

    bool is_open() const { return pid_ > 0; }
    pid_t pid() const { return pid_; }

    /// 返回实际读取字节数，出错返回 -1。
    ssize_t read(uintptr_t addr, void* buf, size_t len) const;

    /// 返回实际写入字节数，出错返回 -1。
    ssize_t write(uintptr_t addr, const void* buf, size_t len) const;

    /// 当前是否在使用 process_vm_* 路径。
    bool using_process_vm() const { return use_process_vm_; }

private:
    pid_t pid_ = -1;
    int mem_fd_ = -1;
    bool use_process_vm_ = false;
};
