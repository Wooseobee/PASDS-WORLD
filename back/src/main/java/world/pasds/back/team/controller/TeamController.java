package world.pasds.back.team.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import world.pasds.back.member.entity.CustomUserDetails;
import world.pasds.back.team.entity.dto.request.*;
import world.pasds.back.team.entity.dto.response.GetTeamMemberResponseDto;
import world.pasds.back.team.entity.dto.response.GetTeamsResponseDto;
import world.pasds.back.team.service.TeamService;

import java.util.List;

@RestController
@RequestMapping("/app/api/team")
@RequiredArgsConstructor
public class TeamController {

    private final TeamService teamService;

    @GetMapping("")
    public ResponseEntity<?> getTeams(@RequestBody GetTeamsRequestDto requestDto, @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<GetTeamsResponseDto> response = teamService.getTeams(requestDto, userDetails.getMemberId());
        return ResponseEntity.ok().body(response);
    }

    @GetMapping("/{teamId}/{offset}")
    public ResponseEntity<?> getTeamMember(@PathVariable(name = "teamId") Long teamId,
                                                   @PathVariable(name = "offset") int offset,
                                                   @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<GetTeamMemberResponseDto> response = teamService.getTeamMember(teamId, offset, userDetails.getMemberId());
        return ResponseEntity.ok().body(response);

    }

    @PostMapping("/create")
    public ResponseEntity<?> createTeam(@RequestBody CreateTeamRequestDto requestDto, @AuthenticationPrincipal CustomUserDetails userDetails) {
        teamService.createTeam(requestDto, userDetails.getMemberId());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/rename")
    public ResponseEntity<?> renameOrganization(@RequestBody RenameTeamRequestDto requestDto, @AuthenticationPrincipal CustomUserDetails userDetails) {
        teamService.renameTeam(requestDto, userDetails.getMemberId());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/delete")
    public ResponseEntity<?> deleteTeam(@RequestBody DeleteTeamRequestDto requestDto, @AuthenticationPrincipal CustomUserDetails userDetails) {
        teamService.deleteTeam(requestDto, userDetails.getMemberId());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/invite")
    public ResponseEntity<?> inviteMemberToTeam(@RequestBody InviteMemberToTeamRequestDto requestDto, @AuthenticationPrincipal CustomUserDetails userDetails) {
        teamService.inviteMemberToTeam(requestDto, userDetails.getMemberId());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/remove")
    public ResponseEntity<?> removeMemberFromTeam(@RequestBody RemoveMemberFromTeamRequestDto requestDto, @AuthenticationPrincipal CustomUserDetails userDetails) {
        teamService.removeMemberFromTeam(requestDto, userDetails.getMemberId());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/leave")
    public ResponseEntity<?> leaveTeam(@RequestBody LeaveTeamRequestDto requestDto, @AuthenticationPrincipal CustomUserDetails userDetails) {
        teamService.leaveTeam(requestDto, userDetails.getMemberId());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/assign")
    public ResponseEntity<?> assignNewLeader(@RequestBody AssignNewTeamHeaderRequestDto requestDto, @AuthenticationPrincipal CustomUserDetails userDetails) {
        teamService.assignNewLeader(requestDto, userDetails.getMemberId());
        return ResponseEntity.ok().build();
    }
}
