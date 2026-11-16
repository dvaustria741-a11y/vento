// SPDX-FileCopyrightText: 2026 Patrick Gaskin
// SPDX-License-Identifier: GPL-3.0-or-later

//! Options the two RDP backends read the same way: the desktop size to ask
//! for and the number of monitors. RDP has no "keep the current size": the
//! size is in the client's connection data, so some size has to be sent.

use crate::options::Options;

/// US English, fixed: the scancode tables both clients send are a US
/// layout, so the server has to be told to use the same layout.
pub const KEYBOARD_LAYOUT: u32 = 0x0409;

/// The size to ask for when nothing better is known: the first connection
/// to a machine under `remote`, and any unparseable value.
pub const FALLBACK_SIZE: (u32, u32) = (1920, 1200);

/// `1920x1200` as its two numbers, or none.
pub fn parse_size(value: &str) -> Option<(u32, u32)> {
    let (w, h) = value.split_once('x')?;
    let (w, h) = (w.parse().ok()?, h.parse().ok()?);
    (w > 0 && h > 0).then_some((w, h))
}

/// The desktop size to ask this connection's server for. `remote` means
/// the size from last time, `last`, which a client may record per address
/// (the phone does), or the fallback when it does not. `device` is the
/// screen the session will be drawn on, if the caller knows it.
pub fn desktop_size(value: &str, screen: Option<(u32, u32)>, last: Option<(u32, u32)>) -> (u32, u32) {
    match value {
        "remote" => last.filter(|&(w, h)| w > 0 && h > 0).unwrap_or(FALLBACK_SIZE),
        "device" => screen
            .filter(|&(w, h)| (200..=4096).contains(&w) && (200..=4096).contains(&h))
            .unwrap_or(FALLBACK_SIZE),
        _ => parse_size(value).unwrap_or(FALLBACK_SIZE),
    }
}

/// What the FreeRDP backend's `Optimizer` switch puts in place of the rows it
/// covers. Each value is one the catalog offers for that row, and each row is
/// one `freerdp_session.c` applies (`applySettings`): `Graphics` gfx is the
/// graphics pipeline with H.264 where the platform has decoders, `Experience`
/// plain is the wallpaper/theming/animation flags plus the modem connection
/// type, `DesktopSize` is the smallest size in `DESKTOP_SIZES`, and
/// `Compression` is the connection-level compression flag.
pub const OPTIMIZER_PRESET: [(&str, &str); 4] = [
    ("Graphics", "gfx"),
    ("Experience", "plain"),
    ("DesktopSize", "1280x800"),
    ("Compression", "true"),
];

/// The options a connection uses: the given ones, with the Optimizer preset
/// on top when `Optimizer` is true. The input is not changed.
pub fn with_optimizer(options: &Options) -> Options {
    let mut tuned = options.clone();
    if tuned.bool("Optimizer") {
        for (key, value) in OPTIMIZER_PRESET {
            tuned.set(key, value);
        }
    }
    tuned
}

/// How many monitors a connection asks for, clamped to 1 to 16, or 1 when
/// the option is not a number (it is a choice list, but a value can be
/// typed in by hand).
pub fn monitor_count(options: &Options) -> u8 {
    options.int("Monitors", 1).clamp(1, 16) as u8
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_optimizer_preset_only_uses_values_the_catalog_offers() {
        use crate::options::find;
        for (key, value) in OPTIMIZER_PRESET {
            let spec = find(crate::catalog::FREERDP.options, key).unwrap();
            assert_eq!(spec.check(value), Ok(()), "{key}={value}");
        }
    }

    #[test]
    fn the_optimizer_replaces_its_rows_when_on_and_leaves_options_alone_when_off() {
        use crate::options::Options;
        let mut o = Options::defaults(crate::catalog::FREERDP.options);
        o.set("Graphics", "bitmap");
        o.set("Scale", "140");
        assert_eq!(with_optimizer(&o), o);

        o.set("Optimizer", "true");
        let tuned = with_optimizer(&o);
        assert_eq!(tuned.get("Graphics"), "gfx");
        assert_eq!(tuned.get("Experience"), "plain");
        assert_eq!(tuned.get("DesktopSize"), "1280x800");
        assert!(tuned.bool("Compression"));
        assert_eq!(tuned.get("Scale"), "140");
        assert_eq!(o.get("Graphics"), "bitmap");
    }

    #[test]
    fn the_desktop_size_option_is_read_as_the_provider_reads_it() {
        assert_eq!(desktop_size("1366x768", None, None), (1366, 768));
        assert_eq!(desktop_size("remote", Some((800, 600)), None), FALLBACK_SIZE);
        assert_eq!(desktop_size("remote", None, Some((1279, 800))), (1279, 800));
        assert_eq!(desktop_size("1366x768", None, Some((1279, 800))), (1366, 768));
        assert_eq!(desktop_size("device", Some((2560, 1440)), None), (2560, 1440));
        assert_eq!(desktop_size("device", Some((100, 100)), None), FALLBACK_SIZE);
        assert_eq!(desktop_size("device", None, None), FALLBACK_SIZE);
        assert_eq!(desktop_size("nonsense", None, None), FALLBACK_SIZE);
        assert_eq!(parse_size("0x5"), None);
    }
}
