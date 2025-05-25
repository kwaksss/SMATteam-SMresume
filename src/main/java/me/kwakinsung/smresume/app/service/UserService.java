package me.kwakinsung.smresume.app.service;

import lombok.RequiredArgsConstructor;
import me.kwakinsung.smresume.app.dto.UserDto;
import me.kwakinsung.smresume.app.frame.ResumeService;
import me.kwakinsung.smresume.app.repository.UserRepository;
import org.springframework.stereotype.Service;


import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService implements ResumeService<String, UserDto> {
    //인터페이스가 될 수 없다.
    //보통 controller가 호출하기 때문.
    //RequiredArgsConstructor를 써주면 CustService 객체가 생성될때 자동으로 custRepository 생성
    //RequiredArgsContructor를 사용하면, 필드를 final로..
    final UserRepository userRepository;

    @Override
    public void add(UserDto usersDto) throws Exception {
        userRepository.insert(usersDto);



    }

    @Override
    public void modify(UserDto usersDto) throws Exception {
        userRepository.update(usersDto);


    }

    @Override
    public void del(String s) throws Exception {
        userRepository.delete(s);

    }

    @Override
    public UserDto get(String s) throws Exception {
        return userRepository.selectOne(s);
    }

    @Override
    public List<UserDto> get() throws Exception {
        return userRepository.select();
    }








}
