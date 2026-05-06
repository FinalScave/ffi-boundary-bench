export class BinaryModel {
  public readonly intValue: number;
  public readonly floatValue: number;
  public readonly stringValue: string;

  constructor(intValue: number, floatValue: number, stringValue: string) {
    this.intValue = intValue;
    this.floatValue = floatValue;
    this.stringValue = stringValue;
  }
}
