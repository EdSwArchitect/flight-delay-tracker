export function getDelayColor(delayMin: number | null | undefined): [number, number, number] {
  if (delayMin == null) return [136, 135, 128];     // grey
  if (delayMin <= 0) return [57, 158, 117];          // green
  if (delayMin <= 30) return [239, 159, 39];         // amber
  if (delayMin <= 60) return [216, 90, 48];          // orange
  return [226, 75, 74];                               // red
}
