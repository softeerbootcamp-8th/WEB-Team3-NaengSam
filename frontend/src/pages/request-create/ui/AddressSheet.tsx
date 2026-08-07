import { useEffect, useRef, useState } from "react";
import {
  BottomSheet,
  Button,
  PlaceItem,
  SegmentedToggle,
  TextField,
  TopBar,
} from "@/shared/ui";
import { api, isApiError, type AddressDto } from "@/shared/api";
import { loadDaumPostcode } from "@/shared/lib";
import type { Meeting } from "./types";

export interface AddressValue {
  /** 도로명 주소(line1). */
  address1: string;
  /** 상세주소(line2). */
  detail: string;
  meeting: Meeting;
}

export interface AddressSheetProps {
  open: boolean;
  /** 시트 제목(예: "픽업지 검색"). */
  label: string;
  /** 편집 시작 시의 현재 값. */
  value: AddressValue;
  onClose: () => void;
  onSubmit: (value: AddressValue) => void;
}

/**
 * 다음 우편번호로 도로명 주소를 검색하거나 저장된 배송지에서 고르고,
 * 상세주소·전달 방식을 입력하는 바텀시트. 픽업/도착지 각각에 사용한다.
 */
export function AddressSheet({
  open,
  label,
  value,
  onClose,
  onSubmit,
}: AddressSheetProps) {
  // StepLocation이 editing 값을 key로 주므로 열릴 때마다 새로 마운트된다.
  const [road, setRoad] = useState(() => value.address1);
  const [detail, setDetail] = useState(() => value.detail);
  const [meeting, setMeeting] = useState<Meeting>(() => value.meeting);
  const embedRef = useRef<HTMLDivElement>(null);

  // 저장된 주소 보기(findAll) 관련 상태.
  const [savedView, setSavedView] = useState(false);
  const [savedList, setSavedList] = useState<AddressDto[]>([]);
  const [savedLoading, setSavedLoading] = useState(false);
  const [savedError, setSavedError] = useState<string | null>(null);

  // 배송지 저장(saveAddress) 관련 상태.
  const [alias, setAlias] = useState("");
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [showAliasToast, setShowAliasToast] = useState(false);

  // 검색 단계(주소 미선택·목록 아님)일 때 우편번호 위젯을 임베드.
  useEffect(() => {
    if (!open || road || savedView) return;
    let cancelled = false;
    loadDaumPostcode().then(() => {
      if (cancelled || !embedRef.current) return;
      embedRef.current.innerHTML = "";
      new window.daum.Postcode({
        oncomplete: (data: { roadAddress: string; jibunAddress: string }) => {
          setRoad(data.roadAddress || data.jibunAddress);
        },
        width: "100%",
        height: "100%",
      }).embed(embedRef.current);
    });
    return () => {
      cancelled = true;
    };
  }, [open, road, savedView]);

  // 저장된 배송지 목록 조회.
  const openSaved = async () => {
    setSavedView(true);
    setSavedLoading(true);
    setSavedError(null);
    try {
      const { result } = await api.findAll();
      setSavedList(result ?? []);
    } catch (e) {
      setSavedError(
        isApiError(e) ? e.message : "저장된 주소를 불러오지 못했어요.",
      );
    } finally {
      setSavedLoading(false);
    }
  };

  // 저장된 주소를 골라 폼에 채운다.
  const selectSaved = (a: AddressDto) => {
    setRoad(a.addressLine1 ?? "");
    setDetail(a.addressLine2 ?? "");
    setSavedView(false);
  };

  // 현재 선택한 주소를 배송지로 저장. alias·addressLine1·addressLine2 모두 필수.
  const onSave = async () => {
    if (!alias.trim() || !detail.trim()) return;
    setSaving(true);
    setSaveError(null);
    try {
      await api.saveAddress({
        addressAlias: alias.trim(),
        addressLine1: road.trim(),
        addressLine2: detail.trim(),
      });
      setSaved(true);
      setShowAliasToast(false);
    } catch (e) {
      setSaveError(isApiError(e) ? e.message : "배송지 저장에 실패했어요.");
    } finally {
      setSaving(false);
    }
  };

  const canSubmit = !!road.trim();

  return (
    <BottomSheet open={open} label={label} onClose={onClose}>
      <TopBar title={label} actions={["close"]} onAction={onClose} />

      {!road && !savedView && (
        <>
          <div className="flex justify-end">
            <button
              type="button"
              onClick={openSaved}
              className="text-sm font-semibold text-teal-700 hover:underline"
            >
              저장된 주소 보기
            </button>
          </div>
          <div
            ref={embedRef}
            className="h-[420px] w-full overflow-hidden rounded-md border border-line"
          />
        </>
      )}

      {!road && savedView && (
        <div className="flex flex-col gap-3">
          <div className="flex items-center justify-between">
            <p className="text-sm font-semibold text-navy-900">저장된 주소</p>
            <button
              type="button"
              onClick={() => setSavedView(false)}
              className="text-sm font-semibold text-teal-700 hover:underline"
            >
              직접 검색하기
            </button>
          </div>

          {savedLoading ? (
            <p className="py-8 text-center text-sm text-muted">불러오는 중…</p>
          ) : savedError ? (
            <p className="py-8 text-center text-sm text-status-danger">
              {savedError}
            </p>
          ) : savedList.length === 0 ? (
            <p className="py-8 text-center text-sm text-muted">
              저장된 주소가 없어요.
            </p>
          ) : (
            <div className="flex flex-col gap-2">
              {savedList.map((a, i) => (
                <PlaceItem
                  key={`${a.addressAlias ?? ""}-${i}`}
                  name={a.addressAlias ?? "저장된 주소"}
                  detail={[a.addressLine1, a.addressLine2]
                    .filter(Boolean)
                    .join(" ")}
                  icon="star"
                  selected={false}
                  onSelect={() => selectSaved(a)}
                />
              ))}
            </div>
          )}
        </div>
      )}

      {road && (
        <div className="flex flex-col gap-4">
          {/* 배송지 저장(닫기 아래 우측 텍스트) */}
          <div className="flex items-center justify-end gap-2">
            {saved && (
              <span className="text-2xs text-teal-700">저장됐어요</span>
            )}
            <button
              type="button"
              onClick={() => {
                setSaveError(null);
                setShowAliasToast(true);
              }}
              className="text-sm font-semibold text-teal-700 hover:underline"
            >
              이 주소 저장
            </button>
          </div>

          <TextField
            label="도로명 주소"
            value={road}
            readOnly
            leadingIcon="pin"
          />

          <TextField
            label="상세주소"
            placeholder="동·층·호수 등 상세주소"
            maxLength={255}
            value={detail}
            onChange={(e) => setDetail(e.target.value)}
          />

          <div className="flex flex-col gap-2">
            <p className="text-sm text-muted">전달 방식</p>
            <SegmentedToggle
              options={["대면", "비대면"]}
              value={meeting}
              onChange={(v) => setMeeting(v as Meeting)}
            />
          </div>

          <div className="flex gap-3">
            <Button
              variant="outline"
              className="px-5"
              onClick={() => setRoad("")}
            >
              주소 다시 검색
            </Button>
            <Button
              variant="navy"
              className="flex-1"
              disabled={!canSubmit}
              onClick={() =>
                onSubmit({ address1: road.trim(), detail, meeting })
              }
            >
              이 위치로 설정
            </Button>
          </div>
        </div>
      )}

      {/* 별칭 입력 토스트 */}
      {showAliasToast && (
        <div className="fixed inset-x-0 bottom-6 z-50 mx-auto max-w-[420px] px-4">
          <div className="flex flex-col gap-2.5 rounded-md bg-navy-900 p-3.5 text-white shadow-elevated">
            <p className="text-base font-bold">별칭을 정해주세요</p>
            <input
              value={alias}
              onChange={(e) => setAlias(e.target.value)}
              maxLength={30}
              placeholder="예: 집, 회사"
              className="w-full rounded-sm bg-white px-3 py-2 text-md text-navy-900 outline-none placeholder:text-muted"
            />
            {!detail.trim() && (
              <p className="text-2xs text-track">
                상세주소를 입력해야 저장할 수 있어요.
              </p>
            )}
            {saveError && (
              <p className="text-2xs text-status-danger">{saveError}</p>
            )}
            <div className="flex items-center gap-2">
              <Button
                variant="outline"
                size="sm"
                className="flex-1"
                onClick={() => setShowAliasToast(false)}
              >
                취소
              </Button>
              <Button
                variant="primary"
                size="sm"
                className="flex-1"
                disabled={saving || !alias.trim() || !detail.trim()}
                onClick={onSave}
              >
                {saving ? "저장 중…" : "저장"}
              </Button>
            </div>
          </div>
        </div>
      )}
    </BottomSheet>
  );
}
