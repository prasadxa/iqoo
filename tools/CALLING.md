# Calling and Contact Selection

## Behavior

- Queries Android's phone-number provider only with READ_CONTACTS permission.
  Stored Aasra contacts still work without phonebook access. No address-book import
  or automatic SOS enrollment takes place.
- Exact names/nicknames rank ahead of family aliases, substrings and bounded
  fuzzy matching. Hindi vowel marks are preserved. Android ICU transliteration
  supports common Hindi-spoken, English-stored names as lower-ranked candidates.
- Same-name numbers remain distinct. Duplicate numbers are collapsed. Device
  mobile/home/work labels are retained, including when duplicated in Aasra's store.
- Multiple matches ask for an option number, number type, full name or final digits.
  A selection is not approval: the chosen number must then be confirmed.
- More than five matches ask for a more specific name instead of silently choosing.
- Confirmation is bound to the resolved number and exact SMS text, consumed once,
  and expires after two minutes on a monotonic clock. A name containing Hindi
  `जी`, unrelated text, stale responses and changed message text cannot approve it.
- Local/hybrid and managed cloud voice use the same coordinator. Cloud reissues
  the original tool arguments after the user's response; local voice intercepts
  the response directly. LLM arguments do not themselves grant confirmation.
- Outgoing call handoff pauses Aasra capture. CALL_PHONE uses ACTION_CALL;
  missing permission uses the dialer and accurately says it opened the dialer.
- SMS permission failure is explicit. SMS submission is not a delivery receipt.

## Permissions and Privacy

Settings > Emergency contacts has an explicit contacts/calling permission action
and a separate SMS permission action. Missing permissions requested by voice tools
also show help on the home screen. A permission grant never retries an old call.

Contact lookup runs locally. In cloud voice, the matching names, number labels and
last four digits appear in tool results sent to CallMissed for the spoken question.
The entire phonebook is not uploaded. SMS text belongs to the requested tool call;
it is not sent until confirmation and Android permission checks succeed.

## Verification

Regression tests cover provider access with/without permission, no provider writes,
same-name/multiple-number ambiguity, duplicate numbers, Hindi/English names and
family aliases, selection followed by confirmation, cancellation/expiry, unchanged
SMS content and prevention of duplicate execution.

On-device tests use synthetic contacts and capture dialing intents instead of
starting the phone app. All 34 app device tests passed after this update. Android's
permission report confirmed Contacts, Phone and SMS permissions on the attached
CPH2573. No real calls or messages were sent as tests.

Actual carrier connection/delivery, dual-SIM prompts and OEM background-activity
restrictions are still system-level acceptance tests. A successful dialing intent
does not prove that the recipient answered. Name matching cannot guarantee perfect
speech recognition; confirmation remains required.
