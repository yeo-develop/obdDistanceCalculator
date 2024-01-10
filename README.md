# ObdDistanceCalculator

Android Device 에서 OBDII 디바이스와 ELM327 프로토콜을 통해 주행 거리 값을 간단히 계산해 볼 수 있는 프로젝트입니다.

# Why?
저는 차를 좋아합니다.<br>특히 내연기관차를 무척이나 좋아합니다. (자차는 없습니다 ㅠ)<br>차량 진단을 위해 OBDII라는 디바이스를 사용한다는걸 현재 다니고있는 회사를 통해 알게되었고,<br>마침 장비도 생겼겠다 그중 제일 만만하고(위키피디아 문서를 뒤져봐도 제일 와닿는 값이 속도값밖에 없긴 했습니다) 제일 dynamic한 값인 속도값을 연동해보았고 이걸로 미터도 측정해보면 재밌겠다 싶어 개발하게 되었습니다.

# Table of Contents
프로젝트의 계층 구조는 다음과 같습니다 
- APP: 실질적으로 OBDLibrary를 사용해보기 위한 playground 용도로 사용합니다. 후술할 OBDLibrary에서 제공되는 function들을 사용해볼 수 있습니다.   
  - MainActivity - OBDLibrary의 기본 사용법에 대해 기술되어 있습니다.
    ![image](https://github.com/yeo-develop/obdDistanceCalculator/assets/143160346/3e61cf30-6973-43d1-8a5e-2284197fea9f)
    실행 시 위와 같은 화면이 보여지며, mac address가 기입되어있는 editText에 macAddress를 기입 후 연결해주면, OBD로부터 지속적으로 속도값을 제공받게 됩니다.
    그 중 "거리 적산 시작" 버튼을 누르면,
    ![image](https://github.com/yeo-develop/obdDistanceCalculator/assets/143160346/07d5f4ef-d5c6-48fa-a4fc-bdf94e02b043)
    위와 같이 속도를 기반으로 거리를 적산하여 TextView에 표시해주게 됩니다.

   
- OBDLibrary: Playground로 사용되는 본 앱과의 분리를 위해 패키지를 분리하였습니다. 해당 모듈만 따로 aar로 빌드 하여 사용하는 것이 권장됩니다.

  > 주의 : Android 12 이상부터 BLUETOOTH_ADMIN 권한과 BLUETOOTH_SCAN, BLUETOOTH_CONNECT 권한을 필요로합니다.<br>본 앱에선 permission을 묻는 depth가 없으니 Android12 이상의 디바이스에선 앱 설정에서 수동으로 허용 처리를 해주세요.

  - OBDConnectionManager : OBD와의 연결 및 속도 정보 요청 및 기반 response를 [SpeedDistanceCalculator]로 송신하는 클래스입니다.
  - SpeedDistanceCalculator : OBD를 통해 받아온 자동차의 속도를 기반으로 차량의 총 주행거리를 계산합니다.
