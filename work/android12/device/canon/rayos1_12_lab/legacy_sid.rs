//! Recover an actual kernel process context on this pre-transaction-SID kernel.
//! Never synthesize a context or skip the caller's UID/policy checks.
use anyhow::{anyhow, ensure, Context, Result};
use std::ffi::CString;
use std::fs::{File, OpenOptions};
use std::io::Read;
use std::os::unix::fs::OpenOptionsExt;
use std::os::unix::io::{AsRawFd, FromRawFd};

fn read_field(directory: &File, relative: &str) -> Result<String> {
    let name = CString::new(relative)?;
    let fd = unsafe {
        libc::openat(directory.as_raw_fd(), name.as_ptr(),
                     libc::O_RDONLY | libc::O_CLOEXEC | libc::O_NOFOLLOW)
    };
    if fd < 0 {
        return Err(std::io::Error::last_os_error()).context("pinned proc attribute open");
    }
    let file = unsafe { File::from_raw_fd(fd) };
    let mut value = String::new();
    file.take(65537).read_to_string(&mut value)?;
    ensure!(value.len() <= 65536, "oversized proc attribute");
    Ok(value)
}

fn effective_uid(directory: &File) -> Result<u32> {
    let status = read_field(directory, "status")?;
    let field = status.lines().find(|line| line.starts_with("Uid:"))
        .and_then(|line| line.split_whitespace().nth(2))
        .ok_or_else(|| anyhow!("missing kernel effective UID"))?;
    Ok(field.parse()?)
}

/// PID/UID must originate from the Binder driver, never from caller arguments.
/// Pin the proc directory so a recycled numeric PID cannot redirect lookups.
/// Fail closed on a dead caller, UID drift, changed context, or unreadable data.
/// This reads the current process context, not a transaction-time SID snapshot;
/// the newer kernel transaction SID is always preferred by the adapter.
pub fn caller_sid(pid: i32, uid: u32) -> Result<CString> {
    ensure!(pid > 0, "caller PID unavailable");
    let directory = OpenOptions::new().read(true)
        .custom_flags(libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_CLOEXEC)
        .open(format!("/proc/{}", pid)).context("pin Binder caller process")?;
    ensure!(effective_uid(&directory)? == uid, "Binder/proc caller UID mismatch");
    let first = read_field(&directory, "attr/current")?;
    ensure!(effective_uid(&directory)? == uid, "caller credentials changed");
    let second = read_field(&directory, "attr/current")?;
    ensure!(first == second, "caller security context changed");
    let sid = first.trim_end_matches(|c| c == '\n' || c == '\0');
    ensure!(!sid.is_empty() && !sid.chars().any(char::is_whitespace), "invalid kernel context");
    Ok(CString::new(sid)?)
}
