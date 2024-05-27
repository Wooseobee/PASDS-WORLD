package world.pasds.back.privateData.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import world.pasds.back.authority.entity.AuthorityName;
import world.pasds.back.common.dto.KmsDecryptionKeysResponseDto;
import world.pasds.back.common.dto.KmsKeyDto;
import world.pasds.back.common.exception.BusinessException;
import world.pasds.back.common.exception.ExceptionCode;
import world.pasds.back.common.service.KeyService;
import world.pasds.back.dashboard.service.OrganizationDashboardService;
import world.pasds.back.dashboard.service.TeamDashboardService;
import world.pasds.back.member.entity.Member;
import world.pasds.back.member.entity.MemberRole;
import world.pasds.back.member.entity.MemberTeam;
import world.pasds.back.member.repository.MemberRepository;
import world.pasds.back.member.repository.MemberRoleRepository;
import world.pasds.back.member.repository.MemberTeamRepository;
import world.pasds.back.organization.entity.Organization;
import world.pasds.back.privateData.entity.DataType;
import world.pasds.back.privateData.entity.dto.PrivateDataRoleDto;
import world.pasds.back.privateData.entity.dto.request.*;
import world.pasds.back.privateData.entity.dto.response.*;
import world.pasds.back.role.entity.Role;
import world.pasds.back.role.repository.RoleAuthorityRepository;
import world.pasds.back.role.repository.RoleRepository;
import world.pasds.back.privateData.entity.PrivateData;
import world.pasds.back.privateData.entity.PrivateDataRole;
import world.pasds.back.team.entity.Team;
import world.pasds.back.privateData.repository.jpa.PrivateDataRepository;
import world.pasds.back.privateData.repository.jpa.PrivateDataRoleRepository;
import world.pasds.back.team.repository.TeamRepository;
import world.pasds.back.team.service.TeamService;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
@RequiredArgsConstructor
public class PrivateDataService {

    private final MemberRepository memberRepository;
    private final TeamRepository teamRepository;
    private final MemberTeamRepository memberTeamRepository;
    private final PrivateDataRepository privateDataRepository;
    private final MemberRoleRepository memberRoleRepository;
    private final PrivateDataRoleRepository privateDataRoleRepository;
    private final RoleRepository roleRepository;
    private final RoleAuthorityRepository roleAuthorityRepository;
    private final KeyService keyService;
    private final OrganizationDashboardService organizationDashboardService;
    private final TeamDashboardService teamDashboardService;
    private final TeamService teamService;
    private final PrivateDataSearchService privateDataSearchService;

    @Transactional
    public GetPrivateDataListResponseDto getPrivateDataList(Long teamId, int offset, Long memberId) {
        Pageable pageable = PageRequest.of(offset - 1, 10);
        Member member = memberRepository.findById(memberId).orElseThrow(() -> new BusinessException(ExceptionCode.MEMBER_NOT_FOUND));
        Team team = teamRepository.findById(teamId).orElseThrow(() -> new BusinessException(ExceptionCode.TEAM_NOT_FOUND));

        // 팀 멤버인지 확인
        MemberTeam findMemberAndTeam = memberTeamRepository.findByMemberAndTeam(member, team);
        if (findMemberAndTeam == null) {
            throw new BusinessException(ExceptionCode.TEAM_UNAUTHORIZED);
        }

        // 팀내에서 역할을 부여받은 사용자인지 확인
        MemberRole findMemberRole = memberRoleRepository.findByMemberAndTeam(member, team);
        if (findMemberRole == null) {
            throw new BusinessException(ExceptionCode.TEAM_UNAUTHORIZED);
        }

        Role role = findMemberRole.getRole();

        // 내가 조회 가능한 비밀 목록 조회
        Page<PrivateData> resultPage = privateDataRepository.findAccessiblePrivateData(teamId, role, pageable);

        List<PrivateDataResponse> canReadData = resultPage.getContent().stream()
                .map(data -> {
                    Member createMember = memberRepository.findById(data.getCreatedBy()).orElse(null);
                    return PrivateDataResponse.builder()
                            .teamId(team.getId())
                            .privateDataId(data.getId())
                            .title(data.getTitle())
                            .type(data.getType())
                            .createdBy(createMember != null ? createMember.getNickname() : "탈퇴한 유저")
                            .dataId(data.getPrivateDataId())
                            .url(data.getUrl())
                            .build();
                })
                .toList();

        return GetPrivateDataListResponseDto.builder()
                .totalPages(resultPage.getTotalPages())
                .privateDataResponse(canReadData)
                .build();
    }

    @Transactional
    public GetPrivateDataResponseDto getPrivateData(Long teamId, Long privateDataId, Long memberId) {
        Member member = memberRepository.findById(memberId).orElseThrow(() -> new BusinessException(ExceptionCode.MEMBER_NOT_FOUND));
        Team team = teamRepository.findById(teamId).orElseThrow(() -> new BusinessException(ExceptionCode.TEAM_NOT_FOUND));

        // 팀 멤버인지 확인
        MemberTeam findMemberAndTeam = memberTeamRepository.findByMemberAndTeam(member, team);
        if (findMemberAndTeam == null) {
            throw new BusinessException(ExceptionCode.TEAM_UNAUTHORIZED);
        }

        PrivateData privateData = privateDataRepository.findById(privateDataId).orElseThrow(() -> new BusinessException(ExceptionCode.PRIVATE_DATA_NOT_FOUND));

        //팀 및 조직의 팀 조회수 증가
        teamDashboardService.checkTeamDashboardDay(teamId);
        teamDashboardService.upTeamDashBoard(teamId, 'v');
        Long organizationId = teamService.getOrganizationId(teamId);
        organizationDashboardService.checkOrganizationDashboardDay(organizationId);
        organizationDashboardService.upOrganizationDashBoard(organizationId, 'v');

        // 요청한 멤버가 해당 팀에서 어떤 역할을 가지는지 확인
        MemberRole memberRole = memberRoleRepository.findByMemberAndTeam(member, team);
        Role role = memberRole.getRole();

        boolean canRead = false;
        boolean canUpdate = false;
        boolean canDelete = false;
        if (privateDataRoleRepository.existsByPrivateDataAndRole(privateData, role)) {  // 역할 확인
            List<AuthorityName> roleAuthorityList = roleAuthorityRepository.findAuthoritiesByRole(role);
            // 해당 비밀을 읽기, 수정, 삭제 권한이 있는지 확인
            canRead = roleAuthorityList.contains(AuthorityName.PRIVATE_DATA_READ);
            canUpdate = roleAuthorityList.contains(AuthorityName.PRIVATE_DATA_UPDATE);
            canDelete = roleAuthorityList.contains(AuthorityName.PRIVATE_DATA_DELETE);
        }

        if (!canRead) {
            throw new BusinessException(ExceptionCode.PRIVATE_DATA_UNAUTHORIZED);
        }

        /**
         * KMS에게 복호화 키 달라고 한 뒤
         * 비밀 복호화하여 응답
         */
        byte[] encryptedDataKey = team.getEncryptedDataKey();
        byte[] encryptedIv = team.getEncryptedIv();
        Long masterKeyVersion = team.getMasterKeyVersion();
        byte[] encryptedPrivateData = privateData.getContent();

        KmsKeyDto dto = KmsKeyDto.builder()
                .encryptedDataKey(Base64.getEncoder().encodeToString(encryptedDataKey))
                .encryptedIv(Base64.getEncoder().encodeToString(encryptedIv))
                .masterKeyVersion(masterKeyVersion)
                .build();
        KmsDecryptionKeysResponseDto decryptKeys = keyService.getKeys(dto);

        byte[] decryptedData = keyService.decryptSecret(encryptedPrivateData,
                Base64.getDecoder().decode(decryptKeys.getDataKey()),
                Base64.getDecoder().decode(decryptKeys.getIv()));

        System.out.println("=============================");
        List<PrivateDataRoleDto> roles = privateDataRoleRepository.findAllByPrivateData(privateData).stream()
                .map(pd -> PrivateDataRoleDto.builder()
                        .roleId(pd.getRole().getId())
                        .name(pd.getRole().getName())
                        .build())
                .toList();

        return GetPrivateDataResponseDto.builder()
                .type(privateData.getType())
                .title(privateData.getTitle())
                .privateData(new String(decryptedData, StandardCharsets.UTF_8))
                .memo(privateData.getMemo())
                .privateDataId(privateData.getPrivateDataId())
                .url(privateData.getUrl())
                .roles(roles)
                .canUpdate(canUpdate)
                .canDelete(canDelete)
                .build();
    }

    @Transactional
    public void createPrivateData(CreatePrivateDataRequestDto requestDto, Long memberId) {
        Member member = memberRepository.findById(memberId).orElseThrow(() -> new BusinessException(ExceptionCode.MEMBER_NOT_FOUND));
        Team team = teamRepository.findById(requestDto.getTeamId()).orElseThrow(() -> new BusinessException(ExceptionCode.TEAM_NOT_FOUND));
        Organization organization = team.getOrganization();

        // 비밀 생성 가능한지 권한 확인
        MemberRole findMemberRole = memberRoleRepository.findByMemberAndTeam(member, team);
        Role role = findMemberRole.getRole();

        if (!roleAuthorityRepository.checkAuthority(role, AuthorityName.PRIVATE_DATA_CREATE)) {
            throw new BusinessException(ExceptionCode.PRIVATE_DATA_UNAUTHORIZED);
        }

        /**
         * KMS에게 암호화 키 달라고 한 뒤
         * 비밀 암호화하여 저장
         */
        byte[] encryptedDataKey = team.getEncryptedDataKey();
        byte[] encryptedIv = team.getEncryptedIv();
        Long masterKeyVersion = team.getMasterKeyVersion();
        KmsKeyDto dto = KmsKeyDto.builder()
                .encryptedDataKey(Base64.getEncoder().encodeToString(encryptedDataKey))
                .encryptedIv(Base64.getEncoder().encodeToString(encryptedIv))
                .masterKeyVersion(masterKeyVersion)
                .build();
        KmsDecryptionKeysResponseDto decryptKeys = keyService.getKeys(dto);

        byte[] encryptedPrivateData = keyService.encryptSecret(requestDto.getContent().getBytes(StandardCharsets.UTF_8),
                Base64.getDecoder().decode(decryptKeys.getDataKey()),
                Base64.getDecoder().decode(decryptKeys.getIv()));

        PrivateData privateData;
        if (requestDto.getType() == DataType.LOGIN) {
            privateData = PrivateData.builder()
                    .team(team)
                    .type(requestDto.getType())
                    .title(requestDto.getTitle())
                    .content(encryptedPrivateData)
                    .memo(requestDto.getMemo())
                    .privateDataId(requestDto.getPrivateDataId())
                    .url(requestDto.getUrl())
                    .count(0)
                    .build();
        } else {
            privateData = PrivateData.builder()
                    .team(team)
                    .type(requestDto.getType())
                    .title(requestDto.getTitle())
                    .content(encryptedPrivateData)
                    .memo(requestDto.getMemo())
                    .count(0)
                    .build();
        }
        PrivateData save = privateDataRepository.save(privateData);
        privateDataSearchService.savePrivateData(save, organization.getId(), organization.getName(), team.getId(), team.getName());

        // 설정하고자 하는 역할 조회
        List<Role> setRoleList = roleRepository.findAllById(requestDto.getRoleId());

        // 조직장과 팀장도 비밀_역할 저장
        Role header = roleRepository.findByTeamAndName(team, "HEADER");
        Role leader = roleRepository.findByTeamAndName(team, "LEADER");

        List<PrivateDataRole> privateDataRoleList = new ArrayList<>();
        privateDataRoleList.add(PrivateDataRole.builder().privateData(privateData).role(header).build());
        privateDataRoleList.add(PrivateDataRole.builder().privateData(privateData).role(leader).build());
        for (Role setRole : setRoleList) {
            // 비밀_역할 저장
            privateDataRoleList
                    .add(PrivateDataRole.builder()
                            .privateData(privateData)
                            .role(setRole)
                            .build());
        }
        privateDataRoleRepository.saveAll(privateDataRoleList);

        //조직 및 팀 비밀 개수 증가
        Long teamId = team.getId();
        teamDashboardService.checkTeamDashboardDay(teamId);
        teamDashboardService.upTeamDashBoard(teamId, 'c');
        Long organizationId = teamService.getOrganizationId(teamId);
        organizationDashboardService.checkOrganizationDashboardDay(organizationId);
        organizationDashboardService.upOrganizationDashBoard(organizationId, 'c');

        team.setSecretCount(team.getSecretCount() + 1);
        teamRepository.save(team);
    }

    @Transactional
    public void updatePrivateData(UpdatePrivateDataRequestDto requestDto, Long memberId) {
        Member member = memberRepository.findById(memberId).orElseThrow(() -> new BusinessException(ExceptionCode.MEMBER_NOT_FOUND));
        Team team = teamRepository.findById(requestDto.getTeamId()).orElseThrow(() -> new BusinessException(ExceptionCode.TEAM_NOT_FOUND));

        // 비밀 수정 가능한지 권한 확인
        MemberRole findMemberRole = memberRoleRepository.findByMemberAndTeam(member, team);
        Role role = findMemberRole.getRole();

        if (!roleAuthorityRepository.checkAuthority(role, AuthorityName.PRIVATE_DATA_UPDATE)) {
            throw new BusinessException(ExceptionCode.PRIVATE_DATA_UNAUTHORIZED);
        }

        PrivateData findPrivateData = privateDataRepository.findById(requestDto.getPrivateDataId()).orElseThrow(() -> new BusinessException(ExceptionCode.PRIVATE_DATA_NOT_FOUND));

        byte[] encryptedDataKey = team.getEncryptedDataKey();
        byte[] encryptedIv = team.getEncryptedIv();

        KmsKeyDto dto = KmsKeyDto.builder()
                .encryptedDataKey(Base64.getEncoder().encodeToString(encryptedDataKey))
                .encryptedIv(Base64.getEncoder().encodeToString(encryptedIv))
                .masterKeyVersion(team.getMasterKeyVersion())
                .build();
        KmsDecryptionKeysResponseDto decryptKeys = keyService.getKeys(dto);

        byte[] encryptedPrivateData = keyService.encryptSecret(requestDto.getContent().getBytes(StandardCharsets.UTF_8),
                Base64.getDecoder().decode(decryptKeys.getDataKey()),
                Base64.getDecoder().decode(decryptKeys.getIv()));

        // 비밀, 제목, 메모, 아이디, url 변경 가능
        if (findPrivateData.getType() == DataType.LOGIN) {
            findPrivateData.setTitle(requestDto.getTitle());
            findPrivateData.setContent(encryptedPrivateData);
            findPrivateData.setMemo(requestDto.getMemo());
            findPrivateData.setUrl(requestDto.getUrl());
            findPrivateData.setPrivateDataId(requestDto.getId());
        } else {
            findPrivateData.setTitle(requestDto.getTitle());
            findPrivateData.setContent(encryptedPrivateData);
            findPrivateData.setMemo(requestDto.getMemo());
        }

        Role header = roleRepository.findByTeamAndName(team, "HEADER");
        Role leader = roleRepository.findByTeamAndName(team, "LEADER");

        List<Long> roleId = requestDto.getRoleId();
        List<Role> findRoleList = new ArrayList<>(roleRepository.findAllById(roleId));
        findRoleList.add(header);
        findRoleList.add(leader);

        List<PrivateDataRole> newPrivateDataRole = new ArrayList<>();
        for (Role r : findRoleList) {
            newPrivateDataRole.add(PrivateDataRole.builder()
                    .privateData(findPrivateData)
                    .role(r)
                    .build());
        }

        List<PrivateDataRole> privateDataRoleList = privateDataRoleRepository.findAllByPrivateData(findPrivateData);
        privateDataRoleRepository.deleteAll(privateDataRoleList);
        privateDataRoleRepository.saveAll(newPrivateDataRole);
        privateDataRepository.save(findPrivateData);
        privateDataSearchService.updatePrivateData(findPrivateData);
    }

    @Transactional
    public void updatePrivateDataRole(UpdatePrivateDataRoleRequestDto requestDto, Long memberId) {
        Member member = memberRepository.findById(memberId).orElseThrow(() -> new BusinessException(ExceptionCode.MEMBER_NOT_FOUND));
        Team team = teamRepository.findById(requestDto.getTeamId()).orElseThrow(() -> new BusinessException(ExceptionCode.TEAM_NOT_FOUND));

        // 비밀_역할 수정 가능한지 권한 확인
        MemberRole findMemberRole = memberRoleRepository.findByMemberAndTeam(member, team);
        Role role = findMemberRole.getRole();

        if (!roleAuthorityRepository.checkAuthority(role, AuthorityName.PRIVATE_DATA_ROLE_UPDATE)) {
            throw new BusinessException(ExceptionCode.PRIVATE_DATA_UNAUTHORIZED);
        }

        PrivateData privateData = privateDataRepository.findById(requestDto.getPrivateDataId()).orElseThrow(() -> new BusinessException(ExceptionCode.PRIVATE_DATA_NOT_FOUND));

        // 팀 역할 리스트
        List<Long> roleIdList = roleRepository.findAllByTeam(team).stream()
                .map(Role::getId).toList();

        // 기존 비밀 역할 리스트 제거
        List<PrivateDataRole> privateDataRoleList = privateDataRoleRepository.findAllByPrivateData(privateData);
        privateDataRoleRepository.deleteAll(privateDataRoleList);

        // 새로운 비밀 역할 리스트
        List<Long> newRoleIdList = requestDto.getRoleId();
        List<Role> findRoleList = roleRepository.findAllById(newRoleIdList);
        List<PrivateDataRole> newPrivateDataRoleList = new ArrayList<>();

        // 팀 내의 역할인지 검증
        for (Role newRole : findRoleList) {
            if (roleIdList.contains(newRole.getId())) {
                newPrivateDataRoleList.add(PrivateDataRole.builder()
                        .privateData(privateData)
                        .role(newRole)
                        .build());
            }
        }
        privateDataRoleRepository.saveAll(newPrivateDataRoleList);
    }

    @Transactional
    public void deletePrivateData(DeletePrivateDataRequestDto requestDto, Long memberId) {
        Member member = memberRepository.findById(memberId).orElseThrow(() -> new BusinessException(ExceptionCode.MEMBER_NOT_FOUND));
        Team team = teamRepository.findById(requestDto.getTeamId()).orElseThrow(() -> new BusinessException(ExceptionCode.TEAM_NOT_FOUND));

        // 비밀 삭제 가능한지 권한 확인
        MemberRole findMemberRole = memberRoleRepository.findByMemberAndTeam(member, team);
        Role role = findMemberRole.getRole();

        if (!roleAuthorityRepository.checkAuthority(role, AuthorityName.PRIVATE_DATA_DELETE)) {
            throw new BusinessException(ExceptionCode.PRIVATE_DATA_UNAUTHORIZED);
        }

        PrivateData privateData = privateDataRepository.findById(requestDto.getPrivateDataId()).orElseThrow(() -> new BusinessException(ExceptionCode.PRIVATE_DATA_NOT_FOUND));
        privateDataRoleRepository.deleteAll(privateDataRoleRepository.findAllByPrivateData(privateData));
        privateDataRepository.delete(privateData);
        privateDataSearchService.deletePrivateData(privateData);

        // 조직 및 팀 비밀 개수 감소
        Long teamId = team.getId();
        teamDashboardService.checkTeamDashboardDay(teamId);
        teamDashboardService.upTeamDashBoard(teamId, 'm');
        Long organizationId = teamService.getOrganizationId(teamId);
        organizationDashboardService.checkOrganizationDashboardDay(organizationId);
        organizationDashboardService.upOrganizationDashBoard(organizationId, 'm');

        team.setSecretCount(team.getSecretCount() - 1);
        teamRepository.save(team);
    }
}
