//! Device acceptance checks for the exact legacy Keystore caller-context helper.
#[path = "rayo_legacy_sid.rs"]
mod legacy_sid;

fn main() -> anyhow::Result<()> {
    let pid = std::process::id() as i32;
    let uid = unsafe { libc::geteuid() };
    let context = legacy_sid::caller_sid(pid, uid)?;
    anyhow::ensure!(!context.as_bytes().is_empty(), "empty context");
    anyhow::ensure!(legacy_sid::caller_sid(pid, uid ^ 1).is_err(), "wrong UID accepted");
    anyhow::ensure!(legacy_sid::caller_sid(0, uid).is_err(), "missing PID accepted");
    println!("RAYO12_PINNED_SID_PASS: actual kernel context; wrong UID and missing PID rejected");
    Ok(())
}
