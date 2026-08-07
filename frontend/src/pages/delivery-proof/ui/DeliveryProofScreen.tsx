import { useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { Button, Card, IconChip, ScreenShell, TopBar } from "@/shared/ui";
import { ROUTES } from "@/shared/config/routes";
import { isApiError } from "@/shared/api";
import { axiosInstance } from "@/shared/api/http/axiosInstance";
import { ProofPhoto } from "./ProofPhoto";
import { ProofSignature } from "./ProofSignature";
import { ProofUpload } from "./ProofUpload";

/**
 * 배송 완료 인증 화면(Figma node 191:1322, 191:1352).
 * ?mode=photo 면 사진 인증, 기본은 수령인 서명 인증입니다(UI 전용).
 *
 * 실 백엔드 모드: `?orderId=&intent=pickup|finish` 가 있으면 /delivery-track 에서 넘어온 실제 인증이다.
 * 실제 사진 파일을 골라 presign(GET /upload/url) → S3 PUT → 전이 API(pickup-finish/finish)를
 * 순서대로 호출한다. pickup 은 배달중(track 배송중)으로, finish 는 배달 완료(complete)로 이어진다.
 */
export function DeliveryProofScreen() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [memo, setMemo] = useState("");

  const isPhoto = params.get("mode") === "photo";
  const orderId = params.get("orderId");
  const intent = params.get("intent");
  const isRealProof =
    (intent === "pickup" || intent === "finish") && Boolean(orderId);

  if (isRealProof && orderId) {
    return <RealDeliveryProof orderId={orderId} intent={intent as ProofIntent} />;
  }

  return (
    <ScreenShell>
      <TopBar title="배송 완료 인증" onBack={() => navigate(-1)} actions={[]} />

      <main className="flex flex-1 flex-col gap-4 pt-4">
        <Card className="flex flex-col items-center gap-2">
          <IconChip name={isPhoto ? "package" : "document"} size={36} />
          <p className="text-base font-bold text-navy-900">
            {isPhoto ? "소형택배 #B-773" : "서류 배송 #B-771"}
          </p>
          <p className="text-2xs text-muted">
            {isPhoto ? "C동 7F 문 앞" : "B동 405호 · 수령인 '민'"}
          </p>
        </Card>

        <h1 className="text-lg font-bold tracking-[-0.4px] text-navy-900">
          {isPhoto ? (
            <>
              놓아둔 위치를
              <br />
              촬영해주세요
            </>
          ) : (
            <>
              수령인 서명을
              <br />
              받아주세요
            </>
          )}
        </h1>

        {isPhoto ? (
          <ProofPhoto memo={memo} onMemoChange={setMemo} />
        ) : (
          <ProofSignature />
        )}

        <Button
          variant="navy"
          block
          onClick={() => navigate(ROUTES.deliveryComplete, { replace: true })}
        >
          {isPhoto ? "사진 첨부 · 배송 종료" : "서명 완료 · 배송 종료"}
        </Button>
      </main>
    </ScreenShell>
  );
}

type ProofIntent = "pickup" | "finish";

/** intent 별 백엔드 전이 설정(사진 용도·엔드포인트·완료 후 이동·문구). */
const PROOF_CONFIG: Record<
  ProofIntent,
  {
    title: string;
    heading: string;
    purpose: string;
    endpoint: (orderId: string) => string;
    button: string;
    next: (orderId: string) => string;
  }
> = {
  pickup: {
    title: "픽업 인증",
    heading: "픽업한 물품을\n촬영해주세요",
    purpose: "PICKUP_CERTIFICATION_IMAGE",
    endpoint: (orderId) => `/api/v1/delivery/orders/${orderId}/pickup-finish`,
    button: "픽업 완료 · 사진 첨부",
    next: (orderId) =>
      `${ROUTES.deliveryTrack}?orderId=${orderId}&status=DELIVERING`,
  },
  finish: {
    title: "배달 완료 인증",
    heading: "전달 완료 사진을\n촬영해주세요",
    purpose: "DELIVERY_CERTIFICATION_IMAGE",
    endpoint: (orderId) => `/api/v1/delivery/orders/${orderId}/finish`,
    button: "전달 완료 · 사진 첨부",
    next: () => ROUTES.deliveryComplete,
  },
};

/**
 * 실제 인증(픽업/전달 완료): 파일 선택은 미리보기만 하고, "픽업 완료 · 사진 첨부" 버튼 클릭 한 번에
 * presign → S3 PUT → 전이 API를 순서대로 실행한다(드리미 본인인증 화면과 동일한 흐름).
 * presign/전이 API는 공통 axios 인스턴스로 호출하지만(생성 클라이언트의 upload/delivery 시그니처가
 * 아직 낡아 purpose/resourceId·photoKey 바디를 실을 수 없기 때문 — 임시 테스트 흐름 한정), S3 PUT은
 * 발급받은 presigned URL로 직접 fetch한다(공통 인스턴스를 타면 host가 앱 자신의 origin으로 바뀜).
 */
function RealDeliveryProof({
  orderId,
  intent,
}: {
  orderId: string;
  intent: ProofIntent;
}) {
  const navigate = useNavigate();
  const cfg = PROOF_CONFIG[intent];
  const [file, setFile] = useState<File | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSubmit = async () => {
    if (!file) return;
    setLoading(true);
    setError(null);
    try {
      // 1) presign 발급 — 인증 용도 + 해당 주문(resourceId=orderId) 으로 스코프 지정.
      const fileName = file.name.replace(/[\\/.]{2,}|[\\/]/g, "_") || "proof.jpg";
      const presign = await axiosInstance.get("/api/v1/upload/url", {
        params: { fileName, purpose: cfg.purpose, resourceId: orderId },
      });
      const { url, key } = presign.data?.result ?? {};
      if (!url || !key) throw new Error("presign 발급에 실패했습니다.");

      // 2) 발급받은 presigned URL로 S3에 직접 PUT(공통 axios 인스턴스 미사용 — 절대 URL 그대로
      //    호출해야 실제 호스트로 간다. path만 떼어 앱 origin으로 보내면 dev-storage에서만 우연히
      //    맞고 실 S3에서는 엉뚱한 곳(CloudFront 등)으로 요청이 나가 깨진다).
      const putRes = await fetch(url, {
        method: "PUT",
        body: file,
        headers: { "Content-Type": file.type || "application/octet-stream" },
      });
      if (!putRes.ok) throw new Error("사진 업로드에 실패했습니다.");

      // 3) 전이 API 호출 — photoKey 를 실어 실제 상태 전이(DB 반영).
      await axiosInstance.post(cfg.endpoint(orderId), { photoKey: key });

      navigate(cfg.next(orderId), { replace: true });
    } catch (e) {
      setError(isApiError(e) ? e.message : "처리에 실패했습니다.");
    } finally {
      setLoading(false);
    }
  };

  return (
    <ScreenShell>
      <TopBar title={cfg.title} onBack={() => navigate(-1)} actions={[]} />

      <main className="flex flex-1 flex-col gap-4 pt-4">
        <Card className="flex flex-col items-center gap-2">
          <IconChip name="package" size={36} />
          <p className="text-base font-bold text-navy-900">{cfg.title}</p>
          <p className="break-all text-2xs text-muted">주문 {orderId}</p>
        </Card>

        <h1 className="whitespace-pre-line text-lg font-bold tracking-[-0.4px] text-navy-900">
          {cfg.heading}
        </h1>

        <ProofUpload
          fileName={file?.name ?? null}
          onFileSelected={(f) => {
            setFile(f);
            setError(null);
          }}
        />

        {error && <p className="text-sm text-status-danger">{error}</p>}

        <Button
          variant="navy"
          block
          disabled={!file || loading}
          onClick={handleSubmit}
        >
          {loading ? "처리 중…" : cfg.button}
        </Button>
      </main>
    </ScreenShell>
  );
}
