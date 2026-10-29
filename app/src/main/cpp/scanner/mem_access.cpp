#include "mem_access.h"

#include <fcntl.h>
#include <sys/uio.h>
#include <unistd.h>

#include <cerrno>
#include <cstdio>
#include <cstring>

MemoryAccess::~MemoryAccess() {
    close();
}

bool MemoryAccess::open(pid_t pid) {
    close();
    if (pid <= 0) return false;

    pid_ = pid;

    // 直接打开 /proc/<pid>/mem。root 域下不需要先 ptrace attach。
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/mem", static_cast<int>(pid));
    mem_fd_ = ::open(path, O_RDWR | O_CLOEXEC);
    if (mem_fd_ >= 0) {
        use_process_vm_ = false;
        return true;
    }

    // 退一步：再尝试只读打开（某些情况下写权限被拒，但读仍可用）
    mem_fd_ = ::open(path, O_RDONLY | O_CLOEXEC);
    if (mem_fd_ >= 0) {
        use_process_vm_ = false;
        return true;
    }

    pid_ = -1;
    return false;
}

void MemoryAccess::close() {
    if (mem_fd_ >= 0) {
        ::close(mem_fd_);
        mem_fd_ = -1;
    }
    pid_ = -1;
    use_process_vm_ = false;
}

ssize_t MemoryAccess::read(uintptr_t addr, void* buf, size_t len) const {
    if (pid_ <= 0) return -1;

    // 优先 process_vm_readv：不暂停目标、批量更高效
    struct iovec local {
        buf, len
    };
    struct iovec remote {
        reinterpret_cast<void*>(addr), len
    };
    ssize_t n = process_vm_readv(pid_, &local, 1, &remote, 1, 0);
    if (n == static_cast<ssize_t>(len)) return n;

    // 部分读取或失败时退回 pread
    if (mem_fd_ >= 0) {
        ssize_t m = pread64(mem_fd_, buf, len, static_cast<off64_t>(addr));
        if (m > 0) return m;
    }
    return n > 0 ? n : -1;
}

ssize_t MemoryAccess::write(uintptr_t addr, const void* buf, size_t len) const {
    if (pid_ <= 0) return -1;

    struct iovec local {
        const_cast<void*>(buf), len
    };
    struct iovec remote {
        reinterpret_cast<void*>(addr), len
    };
    ssize_t n = process_vm_writev(pid_, &local, 1, &remote, 1, 0);
    if (n == static_cast<ssize_t>(len)) return n;

    if (mem_fd_ >= 0) {
        ssize_t m = pwrite64(mem_fd_, buf, len, static_cast<off64_t>(addr));
        if (m > 0) return m;
    }
    return n > 0 ? n : -1;
}
