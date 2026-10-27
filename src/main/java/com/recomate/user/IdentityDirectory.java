package com.recomate.user;

public interface IdentityDirectory {
    ProfileSeed profile(String userId);
    void delete(String userId);
    record ProfileSeed(String email, String nickname) { }
}
