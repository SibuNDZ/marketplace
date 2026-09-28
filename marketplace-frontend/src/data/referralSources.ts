// The "how did you hear about us" options, asked of sellers at both doors
// into a seller account: registration with the seller card, and the
// become-a-seller upgrade in account settings. Shared so the two forms can
// never drift into offering different channels, which would split one
// channel's count across two spellings.
//
// The values are the backend ReferralSource enum names and are stored in the
// database as-is. Never change a value; add a new entry instead. The labels
// are free to be reworded.
export const REFERRAL_SOURCES = [
  { value: 'FACEBOOK', label: 'Facebook' },
  { value: 'TIKTOK', label: 'TikTok' },
  { value: 'INSTAGRAM', label: 'Instagram' },
  { value: 'WHATSAPP', label: 'WhatsApp' },
  { value: 'SEARCH', label: 'Google or another search' },
  { value: 'FRIEND', label: 'A friend or another seller' },
  { value: 'EVENT', label: 'A market or event' },
  { value: 'OTHER', label: 'Somewhere else' },
] as const

export type ReferralSourceValue = typeof REFERRAL_SOURCES[number]['value']
