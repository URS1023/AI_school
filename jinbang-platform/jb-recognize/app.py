"""jb-recognize：答题卡 / 教辅 / 作文图片识别服务（技术路线 3.6，Python）。

这里只实现了 Java 侧依赖的接口契约和任务编排，识别模型留了插槽：
  - locate()        四角定位点 + 二维码解析（答题卡 JBSHEET:{sheetId}:{version} / 教辅 JBBOOK:{book}:{page}）
  - read_student_no 手写学号识别（现网是手写数字，不只是涂卡，见 PRD 6.9）
  - read_choices    手写选项字母 / 判断识别
  - read_scores     红笔得分框手写分数（含小数点）
  - ocr()           作文 / 主观题文字识别
没有接入模型时，这些函数返回空结果；Java 侧会把卷子放进「异常卷」，不会误判分。

接口：
  POST /ocr                          {"image": base64} -> {"text": "..."}
  POST /batches/{batch_id}/recognize {"images":[{"key": 存储键, "data": base64}]}（Java 存图后异步提交，正反面相邻）
                                     -> 识别后回调 Java，images 回传存储键（教师端据此查看原卷）
回调：
  POST {JAVA_BASE}/api/exam/recognition/callback  头 X-Recognition-Token
  {"batchId":..,"reRecognize":false,"pages":[{"pageRef","images","qr","studentNo","answers":{题号:作答},"scores":{题号:分数}}]}
"""
import base64
import os
from typing import Optional

import httpx
from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel

JAVA_BASE = os.environ.get("JB_JAVA_BASE", "http://localhost:8080")
TOKEN = os.environ.get("JB_RECOGNITION_TOKEN", "")

app = FastAPI(title="jb-recognize", version="0.1.0")


class OcrReq(BaseModel):
    image: str


class Located(BaseModel):
    qr: Optional[str] = None
    ok: bool = False


def locate(image: bytes) -> Located:
    """定位点 + 二维码。TODO: 接入 OpenCV 透视校正 + pyzbar 二维码解析。"""
    return Located()


def read_student_no(image: bytes, digits: int = 6) -> Optional[str]:
    """手写学号。TODO: 接入手写数字识别模型。"""
    return None


def read_choices(image: bytes) -> dict:
    """客观题作答 {题号: 'A'|'BD'|'对'}。TODO: 接入手写字母识别模型。"""
    return {}


def read_scores(image: bytes) -> dict:
    """先阅后扫：得分框红笔分数 {题号: 8.5}。TODO: 接入红笔分割 + 手写数字（含小数点）识别。"""
    return {}


def ocr(image: bytes) -> str:
    """作文 / 主观题文字识别。TODO: 接入 PaddleOCR 或云 OCR。"""
    return ""


@app.get("/health")
def health():
    return {"status": "UP", "models": {"locate": False, "student_no": False, "choices": False, "scores": False, "ocr": False}}


@app.post("/ocr")
def ocr_api(req: OcrReq):
    try:
        data = base64.b64decode(req.image)
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(status_code=400, detail="invalid image") from exc
    return {"text": ocr(data)}


class ImageRef(BaseModel):
    key: str
    data: str


class RecognizeReq(BaseModel):
    images: list[ImageRef]


@app.post("/batches/{batch_id}/recognize")
async def recognize(batch_id: int, req: RecognizeReq, x_recognition_token: str = Header(default="")):
    if not TOKEN or x_recognition_token != TOKEN:
        raise HTTPException(status_code=403, detail="forbidden")
    pages = []
    # 正反面相邻两张为一份答卷
    for i in range(0, len(req.images), 2):
        group = req.images[i:i + 2]
        images = [base64.b64decode(g.data) for g in group]
        loc = locate(images[0])
        answers, scores = {}, {}
        for img in images:
            answers.update(read_choices(img))
            scores.update(read_scores(img))
        pages.append({
            "pageRef": f"{batch_id}-{i // 2 + 1}",
            "images": [g.key for g in group],
            "qr": loc.qr,
            "studentNo": read_student_no(images[0]),
            "answers": answers,
            "scores": scores,
        })
    async with httpx.AsyncClient(timeout=60) as client:
        r = await client.post(f"{JAVA_BASE}/api/exam/recognition/callback",
                              headers={"X-Recognition-Token": TOKEN},
                              json={"batchId": batch_id, "reRecognize": False, "pages": pages})
        return r.json()
