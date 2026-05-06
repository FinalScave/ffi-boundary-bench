export class BenchmarkCase {
  public readonly name: string;
  public readonly targetBytes: number;
  public readonly iterations: number;

  constructor(name: string, targetBytes: number, iterations: number) {
    this.name = name;
    this.targetBytes = targetBytes;
    this.iterations = iterations;
  }
}
