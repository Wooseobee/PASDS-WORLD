package world.pasds.back.role.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import world.pasds.back.authority.entity.Authority;
import world.pasds.back.authority.entity.AuthorityDto;
import world.pasds.back.authority.entity.AuthorityName;
import world.pasds.back.authority.repository.AuthorityRepository;
import world.pasds.back.common.exception.BusinessException;
import world.pasds.back.common.exception.ExceptionCode;
import world.pasds.back.member.entity.Member;
import world.pasds.back.member.entity.MemberRole;
import world.pasds.back.member.entity.MemberTeam;
import world.pasds.back.member.repository.MemberRepository;
import world.pasds.back.member.repository.MemberRoleRepository;
import world.pasds.back.member.repository.MemberTeamRepository;
import world.pasds.back.organization.entity.Organization;
import world.pasds.back.privateData.entity.PrivateDataRole;
import world.pasds.back.privateData.repository.jpa.PrivateDataRoleRepository;
import world.pasds.back.role.entity.Role;
import world.pasds.back.role.entity.RoleAuthority;
import world.pasds.back.role.entity.dto.request.CreateRoleRequestDto;
import world.pasds.back.role.entity.dto.request.DeleteRoleRequestDto;
import world.pasds.back.role.entity.dto.request.UpdateRoleRequestDto;
import world.pasds.back.role.entity.dto.response.GetRoleDetailResponseDto;
import world.pasds.back.role.entity.dto.response.GetRoleResponseDto;
import world.pasds.back.role.repository.RoleAuthorityRepository;
import world.pasds.back.role.repository.RoleRepository;
import world.pasds.back.team.entity.Team;
import world.pasds.back.team.entity.dto.request.AssignRoleRequestDto;
import world.pasds.back.team.repository.TeamRepository;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RoleService {

    private final TeamRepository teamRepository;
    private final RoleRepository roleRepository;
    private final RoleAuthorityRepository roleAuthorityRepository;
    private final MemberRepository memberRepository;
    private final MemberRoleRepository memberRoleRepository;
    private final MemberTeamRepository memberTeamRepository;
    private final AuthorityRepository authorityRepository;
    private final PrivateDataRoleRepository privateDataRoleRepository;

    @Transactional
    public List<GetRoleResponseDto> getRole(Long teamId, Long memberId) {
        Member member = memberRepository.findById(memberId).orElseThrow(() -> new BusinessException(ExceptionCode.MEMBER_NOT_FOUND));
        Team team = teamRepository.findById(teamId).orElseThrow(() -> new BusinessException(ExceptionCode.TEAM_NOT_FOUND));
        MemberTeam findMemberAndTeam = memberTeamRepository.findByMemberAndTeam(member, team);

        // 팀원인지 확인
        if (findMemberAndTeam == null) {
            throw new BusinessException(ExceptionCode.MEMBER_UNAUTHORIZED);
        }

        List<Role> teamRoleList = roleRepository.findAllByTeamWithAuthorities(team);

        return teamRoleList.stream().map(r -> {
            List<AuthorityDto> authorities = r.getRoleAuthorities().stream()
                    .map(ra -> AuthorityDto.builder()
                            .id(ra.getAuthority().getId())
                            .name(ra.getAuthority().getName())
                            .build())
                    .collect(Collectors.toList());
            return GetRoleResponseDto.builder()
                    .roleId(r.getId())
                    .name(r.getName())
                    .authorities(authorities)
                    .build();
        }).collect(Collectors.toList());
    }

    @Transactional
    public GetRoleDetailResponseDto getRoleDetail(Long roleId, Long memberId) {
        Member member = memberRepository.findById(memberId).orElseThrow(() -> new BusinessException(ExceptionCode.MEMBER_NOT_FOUND));
        Role role = roleRepository.findById(roleId).orElseThrow(() -> new BusinessException(ExceptionCode.ROLE_NOT_FOUND));
        List<RoleAuthority> roleAuthorityList = roleAuthorityRepository.findAllByRole(role);
        List<AuthorityDto> authorityDtoList = new ArrayList<>();
        for (RoleAuthority roleAuthority : roleAuthorityList) {
            Authority authority = roleAuthority.getAuthority();
            authorityDtoList.add(AuthorityDto.builder().id(authority.getId()).name(authority.getName()).build());
        }

        return GetRoleDetailResponseDto.builder()
                .name(role.getName())
                .authorities(authorityDtoList)
                .build();
    }

    @Transactional
    public void createRole(CreateRoleRequestDto requestDto, Long memberId) {
        Member member = memberRepository.findById(memberId).orElseThrow(() -> new BusinessException(ExceptionCode.MEMBER_NOT_FOUND));
        Team team = teamRepository.findById(requestDto.getTeamId()).orElseThrow(() -> new BusinessException(ExceptionCode.TEAM_NOT_FOUND));
        MemberRole memberRole = memberRoleRepository.findByMemberAndTeam(member, team);
        Role role = memberRole.getRole();
        List<String> roleAuthorityList = roleAuthorityRepository.findAllByRole(role)
                .stream()
                .map(roleAuthority -> String.valueOf(roleAuthority.getAuthority().getName()))
                .toList();

        // 권한 확인
        if (!roleAuthorityList.contains(String.valueOf(AuthorityName.ROLE_CREATE))) {
            throw new BusinessException(ExceptionCode.TEAM_UNAUTHORIZED);
        }

        Role createRole = Role.builder()
                .name(requestDto.getRoleName())
                .team(team)
                .build();

        if ("HEADER".equals(createRole.getName()) ||
                "LEADER".equals(createRole.getName()) ||
                "GUEST".equals(createRole.getName())) {
            throw new BusinessException(ExceptionCode.AUTHORITY_NAME_CONFLICT);
        }
        Role savedRole = roleRepository.save(createRole);

        List<RoleAuthority> saveRoleAuthorityList = new ArrayList<>();
        for (Long authorityId : requestDto.getAuthorities()) {
            Authority authority = authorityRepository.findById(authorityId).orElseThrow(() -> new BusinessException(ExceptionCode.AUTHORITY_NOT_FOUND));
            if (AuthorityName.TEAM_DELETE == authority.getName()) {    // 팀 삭제 권한이 있는 역할 생성 불가
                throw new BusinessException(ExceptionCode.BAD_REQUEST);
            }
            RoleAuthority roleAuthority = RoleAuthority.builder()
                    .role(savedRole)
                    .authority(authority)
                    .build();
            saveRoleAuthorityList.add(roleAuthority);
        }

        roleAuthorityRepository.saveAll(saveRoleAuthorityList);
    }

    @Transactional
    public void updateRole(UpdateRoleRequestDto requestDto, Long memberId) {
        Member member = memberRepository.findById(memberId).orElseThrow(() -> new BusinessException(ExceptionCode.MEMBER_NOT_FOUND));
        Team team = teamRepository.findById(requestDto.getTeamId()).orElseThrow(() -> new BusinessException(ExceptionCode.TEAM_NOT_FOUND));
        MemberRole memberRole = memberRoleRepository.findByMemberAndTeam(member, team);
        Role role = memberRole.getRole();
        List<String> roleAuthorityList = roleAuthorityRepository.findAllByRole(role)
                .stream()
                .map(roleAuthority -> String.valueOf(roleAuthority.getAuthority().getName()))
                .toList();

        // 권한 확인
        if (!roleAuthorityList.contains(String.valueOf(AuthorityName.ROLE_UPDATE))) {
            throw new BusinessException(ExceptionCode.TEAM_UNAUTHORIZED);
        }

        // 변경하고자 하는 역할 조회
        Role newRole = roleRepository.findById(requestDto.getRoleId()).orElseThrow(() -> new BusinessException(ExceptionCode.ROLE_NOT_FOUND));

        // 역할명 수정하는 경우
        if (!newRole.getName().equals(requestDto.getNewRoleName())) {
            // 기본 역할 수정 불가 + 기본 역할 이름으로 수정 불가
            if ("HEADER".equals(newRole.getName()) ||
                    "LEADER".equals(newRole.getName()) ||
                    "GUEST".equals(newRole.getName()) ||
                    "HEADER".equals(requestDto.getNewRoleName()) ||
                    "LEADER".equals(requestDto.getNewRoleName()) ||
                    "GUEST".equals(requestDto.getNewRoleName())) {
                throw new BusinessException(ExceptionCode.ROLE_UNAUTHORIZED);
            }
            // 이미 존재하는 역할명으로 수정 불가
            if (roleRepository.existsByTeamAndName(team, requestDto.getNewRoleName())) {
                throw new BusinessException(ExceptionCode.ROLE_EXISTS);
            }
        }

        newRole.setName(requestDto.getNewRoleName());
        roleRepository.save(newRole);

        List<Authority> authorityList = authorityRepository.findAllById(requestDto.getAuthorities());

        // 역할의 권한 수정
        List<RoleAuthority> newRoleAuthorities = authorityList
                .stream()
                .filter(authority -> AuthorityName.TEAM_DELETE != authority.getName())  // 팀 삭제 권한 부여 불가
                .map(authority -> RoleAuthority
                        .builder()
                        .role(newRole)
                        .authority(authority)
                        .build()).toList();
        List<RoleAuthority> findRoleAuthorityList = roleAuthorityRepository.findAllByRole(newRole);
        roleAuthorityRepository.deleteAll(findRoleAuthorityList);
        roleAuthorityRepository.saveAll(newRoleAuthorities);
    }

    @Transactional
    public void deleteRole(DeleteRoleRequestDto requestDto, Long memberId) {
        Member member = memberRepository.findById(memberId).orElseThrow(() -> new BusinessException(ExceptionCode.MEMBER_NOT_FOUND));
        Team team = teamRepository.findById(requestDto.getTeamId()).orElseThrow(() -> new BusinessException(ExceptionCode.TEAM_NOT_FOUND));
        MemberRole memberRole = memberRoleRepository.findByMemberAndTeam(member, team);
        Role role = memberRole.getRole();
        List<String> roleAuthorityList = roleAuthorityRepository.findAllByRole(role)
                .stream()
                .map(roleAuthority -> String.valueOf(roleAuthority.getAuthority().getName()))
                .toList();

        // 권한 확인
        if (!roleAuthorityList.contains(String.valueOf(AuthorityName.ROLE_DELETE))) {
            throw new BusinessException(ExceptionCode.TEAM_UNAUTHORIZED);
        }

        Role deleteRole = roleRepository.findById(requestDto.getRoleId()).orElseThrow(() -> new BusinessException(ExceptionCode.ROLE_NOT_FOUND));

        if ("HEADER".equals(deleteRole.getName()) ||
                "LEADER".equals(deleteRole.getName()) ||
                "GUEST".equals(deleteRole.getName())) {
            throw new BusinessException(ExceptionCode.ROLE_UNAUTHORIZED);
        }

        // 역할을 가진 팀원이 존재할 경우 삭제 불가능
        if (memberRoleRepository.existsByRole(deleteRole)) {
            throw new BusinessException(ExceptionCode.ROLE_MEMBER_EXISTS);
        }

        List<PrivateDataRole> pridvateDataRoleList = privateDataRoleRepository.findAllByRole(deleteRole);
        privateDataRoleRepository.deleteAll(pridvateDataRoleList);

        List<RoleAuthority> deleteRoleAuthorityList = roleAuthorityRepository.findAllByRole(deleteRole);
        roleAuthorityRepository.deleteAll(deleteRoleAuthorityList);

        roleRepository.delete(deleteRole);
    }

    @Transactional
    public void assignRole(AssignRoleRequestDto requestDto, Long memberId) {
        Member assignedMember = memberRepository.findById(requestDto.getAssignedMemberId()).orElseThrow(() -> new BusinessException(ExceptionCode.MEMBER_NOT_FOUND));
        Member member = memberRepository.findById(memberId).orElseThrow(() -> new BusinessException(ExceptionCode.MEMBER_NOT_FOUND));
        Team team = teamRepository.findById(requestDto.getTeamId()).orElseThrow(() -> new BusinessException(ExceptionCode.TEAM_NOT_FOUND));
        Role role = roleRepository.findById(requestDto.getRoleId()).orElseThrow(() -> new BusinessException(ExceptionCode.ROLE_NOT_FOUND));
        Organization organization = team.getOrganization();

        // 팀장과 조직장만이 팀내 역할 수정 가능
        if (!(team.getLeader() != null && (team.getLeader().getId().equals(member.getId())) || organization.getHeader().getId().equals(member.getId()))) {
            throw new BusinessException(ExceptionCode.TEAM_UNAUTHORIZED);
        }

        // 팀에 속한 멤버인지 확인
        MemberTeam findMemberAndTeam = memberTeamRepository.findByMemberAndTeam(assignedMember, team);
        if (findMemberAndTeam == null) {
            throw new BusinessException(ExceptionCode.TEAM_MEMBER_NOT_FOUND);
        }

        // 팀장 부여인 경우
        if (role.getName().equals("LEADER")) {
            // 팀에 리더가 존재하는지 확인
            MemberRole teamAndRole = memberRoleRepository.findByTeamAndRole(team, role);
            if (teamAndRole != null) { // GUEST로 교환
                Role guestRole = roleRepository.findByTeamAndName(team, "GUEST");
                teamAndRole.setRole(guestRole);
                memberRoleRepository.save(teamAndRole);
            }
            team.setLeader(assignedMember);
            teamRepository.save(team);
        }

        // 이미 역할을 부여받은 멤버인지 확인
        MemberRole findMemberAndRole = memberRoleRepository.findByMemberAndTeam(assignedMember, team);
        if (findMemberAndRole == null) {
            MemberRole memberRole = MemberRole.builder()
                    .member(assignedMember)
                    .role(role)
                    .build();
            memberRoleRepository.save(memberRole);
        } else {
            findMemberAndRole.setRole(role);
            memberRoleRepository.save(findMemberAndRole);
        }
    }
}
