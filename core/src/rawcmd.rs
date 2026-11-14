//! Parsing of raw TeamSpeak command text (`name key=value key2 | key=value2`), used for the
//! commands tsclientlib does not model, e.g. the TeamSpeak 6 stream notifications.

use std::collections::BTreeMap;

/// One parsed command: its name and the key/value pairs of every `|`-separated part.
#[derive(Debug, Clone, PartialEq)]
pub struct RawCommand {
    pub name: String,
    pub parts: Vec<BTreeMap<String, String>>,
}

impl RawCommand {
    pub fn parse(text: &str) -> Option<Self> {
        let text = text.trim();
        let (name, rest) = match text.find(' ') {
            Some(i) => (&text[..i], &text[i + 1..]),
            None => (text, ""),
        };
        if name.is_empty() || name.contains('=') {
            return None;
        }
        let parts = rest
            .split('|')
            .map(|part| {
                part.split(' ')
                    .filter(|kv| !kv.is_empty())
                    .map(|kv| match kv.find('=') {
                        Some(i) => (kv[..i].to_string(), unescape(&kv[i + 1..])),
                        None => (kv.to_string(), String::new()),
                    })
                    .collect()
            })
            .collect();
        Some(Self { name: name.to_string(), parts })
    }
}

/// Reverses TeamSpeak's command escaping (`\s` space, `\p` pipe, `\/` slash, `\n` …).
pub fn unescape(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut chars = s.chars();
    while let Some(c) = chars.next() {
        if c != '\\' {
            out.push(c);
            continue;
        }
        match chars.next() {
            Some('s') => out.push(' '),
            Some('p') => out.push('|'),
            Some('/') => out.push('/'),
            Some('\\') => out.push('\\'),
            Some('n') => out.push('\n'),
            Some('r') => out.push('\r'),
            Some('t') => out.push('\t'),
            Some('f') => out.push('\x0c'),
            Some('a') => out.push('\x07'),
            Some('b') => out.push('\x08'),
            Some('v') => out.push('\x0b'),
            Some(other) => out.push(other),
            None => out.push('\\'),
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_stream_notification() {
        let c = RawCommand::parse(
            r#"notifystreamsignaling clid=9 id=2d63 json={"args":{"sdp":"candidate:1\s1\sudp"},"cmd":"iceCandidate"}"#,
        )
        .unwrap();
        assert_eq!(c.name, "notifystreamsignaling");
        assert_eq!(c.parts[0]["clid"], "9");
        assert_eq!(c.parts[0]["json"], r#"{"args":{"sdp":"candidate:1 1 udp"},"cmd":"iceCandidate"}"#);
    }

    #[test]
    fn keeps_sdp_line_breaks_and_empty_values() {
        let c = RawCommand::parse(r"notifyrespondjoinstreamrequest clid=1 msg decision=1 offer=v=0\r\no=-\s1\r\n").unwrap();
        assert_eq!(c.parts[0]["msg"], "");
        assert_eq!(c.parts[0]["offer"], "v=0\r\no=- 1\r\n");
    }

    #[test]
    fn multiple_parts() {
        let c = RawCommand::parse("notifyx a=1|a=2").unwrap();
        assert_eq!(c.parts.len(), 2);
        assert_eq!(c.parts[1]["a"], "2");
    }
}
