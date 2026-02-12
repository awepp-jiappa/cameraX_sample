# CameraX Photo App (Phone/Tablet)

안드로이드 스마트폰/태블릿에서 **CameraX로 사진 촬영**하는 앱.
- Gate 화면 → "사진찍기" 선택 → Camera 화면 진입
- Camera 화면 UI
  - 상단 좌측: 플래시 아이콘
  - 상단 우측: 닫기(X) 버튼
  - 하단 중앙: 촬영 버튼
- 기기 회전(세로/가로/태블릿) 시 UI가 자연스럽게 대응
- 촬영 후 저장 경로: `/sdcard/Download/codex_app/cameraX`

---

## 0. 기술 스택 / 기준
- Language: Kotlin
- UI: (선택) XML + ViewBinding  **권장(빠르고 안정적)**
- Camera: AndroidX CameraX (Preview + ImageCapture)
- Min SDK: 26+ 권장
- Target SDK: 최신 유지

---

## 1. 개발 순서 (이 순서대로 진행)
> 각 단계는 PR 단위로 쪼개서 진행 (PR-1, PR-2 …)

### PR-1) 프로젝트 스캐폴딩
- [ ] Empty Activity 프로젝트 생성
- [ ] 패키지명/앱명 확정
- [ ] ViewBinding 활성화
- [ ] 기본 테마/색상/아이콘 세팅
- [ ] 기본 폴더 구조 생성
  - `ui/gate/`
  - `ui/camera/`
  - `camera/` (CameraX 래퍼/유틸)
  - `storage/` (저장 유틸)

### PR-2) Gate 화면 구현
- [ ] GateActivity(or GateFragment) 생성
- [ ] "사진찍기" 버튼 추가
- [ ] 버튼 클릭 → CameraActivity 진입
- [ ] (옵션) 권한이 없으면 Gate에서 먼저 권한 안내/요청

### PR-3) Camera 화면 UI 뼈대 + Preview 연결 + 회전 대응
- [x] CameraActivity 생성
- [x] UI 배치 (PreviewView + 상단바 + 하단 촬영 버튼)
- [x] 회전 대응 방식 결정 (아래 2가지 중 1)
  - A안(권장): `layout/` + `layout-land/` 각각 XML 제공
  - B안: ConstraintLayout 단일 + ConstraintSet 전환
- [x] 상태바/네비바 인셋 처리(겹침 방지)
- [x] `ProcessCameraProvider` + `PreviewView` 프리뷰 바인딩 (후면 카메라)
- [x] 회전 시 `targetRotation` 업데이트

### PR-4) CameraX 프리뷰 붙이기
- [ ] `ProcessCameraProvider` 초기화
- [ ] `Preview`를 `PreviewView`에 연결
- [ ] 전/후면은 기본 후면(Back)으로 고정(필요 시 확장)
- [ ] lifecycle 안전하게 bind/unbind 처리
- [ ] 회전 시 `targetRotation` 업데이트

### PR-5) 촬영(ImageCapture) + 저장 로직(Downloads 하위)
- [ ] 촬영 버튼 → 사진 촬영
- [ ] 저장 경로: `Download/codex_app/cameraX`
- [ ] 파일명 규칙: `IMG_yyyyMMdd_HHmmss.jpg`
- [ ] Android 10+(Q) 이상:
  - MediaStore(Downloads) 사용
  - `RELATIVE_PATH = "Download/codex_app/cameraX"`
- [ ] Android 9 이하:
  - `Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)` 하위 폴더 생성
  - 필요 권한 처리(WRITE_EXTERNAL_STORAGE)
- [ ] 촬영 성공/실패 Toast 또는 스낵바

### PR-6) 플래시 토글 동작
- [ ] 플래시 아이콘 탭 시 모드 순환
  - `OFF → ON → AUTO` (기본 3단 권장)
- [ ] ImageCapture flashMode 반영
- [ ] 플래시 지원 여부 체크(미지원이면 비활성/숨김)

### PR-7) 닫기 버튼 동작 + 정리
- [ ] 닫기(X) → Activity finish()
- [ ] 카메라 리소스 해제(unbindAll)
- [ ] 회전/백그라운드 복귀 등 예외 상황에서도 안정적으로 동작

### PR-8) QA 체크 + 문서화
- [ ] 폰(세로/가로) UI 확인
- [ ] 태블릿(세로/가로) UI 확인
- [ ] 저장 경로에 실제 파일 생성 확인
- [ ] 권한 거부 시 UX 확인
- [ ] README에 스크린샷/설정/알려진 이슈 기록

---

## 2. UI/회전 대응 규칙(결정사항)
### 권장안: `layout` / `layout-land` 2벌로 간다
- 세로: 상단바(좌 플래시 / 우 닫기), 하단 중앙 촬영
- 가로: 상단바를 좌/우로 유지하되, 안전 영역/인셋 고려하여 위치 조정
- 태블릿: 화면이 넓으므로 상단바/셔터 크기만 적절히 확장

---

## 3. 저장 경로 정책 (중요)
목표 경로: `/sdcard/Download/codex_app/cameraX`

### Android 10(Q) 이상 (Scoped Storage)
- 직접 파일 경로로 쓰지 말고 **MediaStore Downloads**로 저장한다.
- `RELATIVE_PATH = "Download/codex_app/cameraX"` 로 지정하면
  최종적으로 사용자가 보는 경로는 **Download/codex_app/cameraX**가 된다.

### Android 9 이하
- public Downloads 폴더 하위에 디렉토리 생성 후 파일 저장
- 필요 시 `WRITE_EXTERNAL_STORAGE` 권한 처리

---

## 4. 권한
- 필수: `android.permission.CAMERA`
- Android 9 이하에서 public Downloads 직접 파일 저장 시:
  - `android.permission.WRITE_EXTERNAL_STORAGE`
- Android 10+에서 MediaStore Downloads로 저장은 보통 추가 권한 없이 가능(앱이 생성하는 항목)하지만,
  기기/정책 차이 가능성은 테스트로 확인.

---

## 5. Done 정의(완료 기준)
- Gate → Camera 진입 가능
- 프리뷰 정상
- 촬영 버튼 누르면 사진이 저장됨
- 저장 위치가 `Download/codex_app/cameraX` 아래로 들어감
- 회전 시 UI가 깨지지 않고 정상 배치됨
- 플래시/닫기 동작 정상

---
