# 생활 안내 Firebase 연결

생활 가이드의 로컬 기능은 Firebase 설정 없이 실행됩니다. 같은 Firebase 프로젝트에서
Firebase Authentication과 Cloud Firestore를 설정하고, 로그인 및 가족 연결 담당 코드와
아래 경로를 합의하면 공유 일정 화면을 연결할 수 있습니다.

1. 최종 앱의 applicationId로 Firebase Android 앱을 등록합니다. 팀의
   google-services.json과 Google Services Gradle 플러그인을 최종 앱에 적용합니다.
   기존 로그인 기능은 Firebase 기본 앱으로 인증해야 합니다.
2. life-guide.firestore.rules의 두 match 블록 및 필요한 조건 함수를 팀의 기존 규칙에
   통합합니다. 기존 규칙을 통째로 교체하지 않습니다. 별도의 테스트 계정으로
   미연결 가족 거부, 연결 가족 읽기·수정 허용, 권한 회수 후 거부를 확인합니다.
3. 어르신이 일정 수정에 동의하면, 연결 화면에서 어르신 계정으로
   lifeGuideUsers/{elderUid}/scheduleEditors/{familyUid}에 {active: true}를 기록합니다.
   동의를 철회하면 해당 문서를 삭제합니다. 가족 계정은 이 권한을 부여할 수 없습니다.
4. 가족 화면에서 연결이 확인된 elderUid를 지정하여 생활 일정 화면을 엽니다.

```kotlin
startActivity(Intent(this, MainActivity::class.java).apply {
    putExtra(FirebaseLifeSchedules.ELDER_UID, linkedElderUid)
})
```

어르신 로그인 상태에서는 elderUid를 넘기지 않고 화면을 엽니다. 어르신 자신의
lifeGuideUsers/{elderUid}/schedules 아래에 일정이 저장되고, 가족 수정 사항은
어르신 화면의 Firestore 구독에 반영됩니다. 어르신 앱에서 서버 변경을 받은 뒤
기기의 예약 알림도 갱신됩니다. 앱이 닫혀 있는 동안 새 원격 수정을 즉시 전달하려면
통합 단계에서 서버 푸시를 별도로 연결해야 합니다. 로그인 전 기기에서 만든 일정은
별도 로컬 데이터입니다.

공유 일정은 새 규칙과 팀 로그인·가족 연결 코드가 적용된 뒤 두 계정으로 검증합니다.
