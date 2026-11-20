-- 答题卡版面区域坐标（单位 pt，左上角为原点）：识别服务据此切图和定位得分框
ALTER TABLE ex_sheet_version ADD COLUMN regions_json TEXT;
