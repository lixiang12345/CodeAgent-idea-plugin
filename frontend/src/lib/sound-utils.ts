// WebAudio-synthesized notification sound

let audioContext: AudioContext | null = null;

function getAudioContext(): AudioContext | null {
  if (typeof window === "undefined") return null;
  if (!audioContext) {
    const AudioContextClass = window.AudioContext || (window as any).webkitAudioContext;
    if (!AudioContextClass) return null;
    audioContext = new AudioContextClass();
  }
  return audioContext;
}

export function playNotificationChime(): void {
  const ctx = getAudioContext();
  if (!ctx) return;

  const now = ctx.currentTime;
  const oscillator = ctx.createOscillator();
  const gain = ctx.createGain();

  oscillator.connect(gain);
  gain.connect(ctx.destination);

  // Two-tone pleasant chime: 880Hz → 1046Hz (A5 → C6)
  oscillator.frequency.setValueAtTime(880, now);
  oscillator.frequency.linearRampToValueAtTime(1046, now + 0.08);

  // Quick attack, smooth release
  gain.gain.setValueAtTime(0, now);
  gain.gain.linearRampToValueAtTime(0.15, now + 0.01);
  gain.gain.exponentialRampToValueAtTime(0.01, now + 0.25);

  oscillator.start(now);
  oscillator.stop(now + 0.3);
}

export function canPlaySound(): boolean {
  return getAudioContext() !== null;
}
