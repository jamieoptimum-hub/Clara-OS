package com.jarvis.smarthomelauncher.widgets.football.Models

data class Fixture(val id: Int, val date: String, val timestamp: Long)
data class League(val id: Int, val name: String, val round: String)
data class Team(val id: Int, val name: String, val logo: String)
data class Teams(val home: Team, val away: Team)
data class Goals(val home: Int?, val away: Int?)
data class Score(val fulltime: Goals)

data class ApiFootballFixture(val fixture: Fixture, val league: League, val teams: Teams, val goals: Goals, val score: Score)

data class ApiFootballResponse<T>(val get: String, val results: Int, val response: List<T>)
