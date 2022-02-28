import { NativeModules } from 'react-native';

export type ThumbnailResult = {
  uri: string;
  width: number;
  height: number;
};

export type ThumbnailBase64Result = {
  base64: string;
  width: number;
  height: number;
};

type PdfThumbnailType = {
  generate(filePath: string, page: number): Promise<ThumbnailResult>;
  generateWithBase64(base64: string, page: number): Promise<ThumbnailBase64Result>;
  generateAllPages(filePath: string): Promise<ThumbnailResult[]>;
};

const { PdfThumbnail } = NativeModules;

export default PdfThumbnail as PdfThumbnailType;
