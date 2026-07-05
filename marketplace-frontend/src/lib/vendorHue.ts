// src/lib/vendorHue.ts
// Golden-angle rotation: consecutive vendor ids spread maximally around the
// hue wheel so no two adjacent vendors share a stripe colour.
export function vendorHue(vendorId: number): string {
  const hue = Math.round((vendorId * 137.508) % 360)
  return `hsl(${hue} 52% 42%)`
}
