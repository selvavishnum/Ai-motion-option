from __future__ import annotations

from pydantic import BaseModel, Field


class LandmarkOut(BaseModel):
    x: float
    y: float
    z: float


class HandResult(BaseModel):
    handedness: str
    confidence: float
    gesture: str
    action: str | None = None
    landmarks: list[LandmarkOut]


class DetectResponse(BaseModel):
    hands: list[HandResult] = Field(default_factory=list)


class GestureInfo(BaseModel):
    gesture: str
    action: str | None


class HealthResponse(BaseModel):
    status: str = "ok"
