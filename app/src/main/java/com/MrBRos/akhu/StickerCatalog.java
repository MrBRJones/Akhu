package com.MrBRos.akhu;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Emoji stickers for the motion editor, each with words the sticker search matches. */
final class StickerCatalog {

    private static final String[][] STICKERS = {
            {"❤️", "heart love red"}, {"🧡", "heart love orange"}, {"💛", "heart love yellow"},
            {"💚", "heart love green"}, {"💙", "heart love blue"}, {"💜", "heart love purple"},
            {"🖤", "heart love black"}, {"🤍", "heart love white"}, {"💖", "heart love sparkle pink"},
            {"💘", "heart love arrow cupid"}, {"💔", "heart broken heartbreak sad"}, {"💕", "hearts love two pink"},
            {"😀", "happy smile grin face"}, {"😂", "laugh lol tears joy face"}, {"🤣", "laugh rofl lol face"},
            {"😊", "happy smile blush face"}, {"😍", "love heart eyes face"}, {"🥰", "love hearts face"},
            {"😘", "kiss love face"}, {"😎", "cool sunglasses face"}, {"🤩", "star struck wow face"},
            {"🥳", "party birthday celebrate face"}, {"😜", "wink tongue silly face"}, {"🤪", "crazy silly zany face"},
            {"😇", "angel halo innocent face"}, {"🤔", "thinking hmm face"}, {"🙄", "eye roll face"},
            {"😴", "sleep tired zzz face"}, {"😭", "cry sad tears face"}, {"😢", "sad cry tear face"},
            {"😡", "angry mad rage face"}, {"🤯", "mind blown shocked face"}, {"😱", "scream shocked scared face"},
            {"🥺", "pleading puppy eyes face"}, {"😈", "devil evil horns face"}, {"💀", "skull dead lol"},
            {"👻", "ghost boo halloween"}, {"👽", "alien ufo"}, {"🤖", "robot"}, {"💩", "poop"},
            {"👍", "thumbs up like yes ok"}, {"👎", "thumbs down dislike no"}, {"👏", "clap applause"},
            {"🙌", "hands raise celebrate yay"}, {"🙏", "pray please thanks"}, {"✌️", "peace victory"},
            {"🤘", "rock on metal"}, {"👋", "wave hi hello bye"}, {"💪", "strong muscle flex"},
            {"👀", "eyes look see"}, {"🔥", "fire hot lit flame"}, {"✨", "sparkles magic shine"},
            {"⭐", "star"}, {"🌟", "star glow shine"}, {"💫", "dizzy star"}, {"⚡", "lightning bolt zap"},
            {"💥", "boom bang explosion"}, {"💯", "hundred 100 perfect"}, {"🎉", "party popper celebrate tada"},
            {"🎊", "confetti party celebrate"}, {"🎈", "balloon party birthday"}, {"🎂", "cake birthday"},
            {"🎁", "gift present birthday"}, {"🏆", "trophy win award"}, {"👑", "crown king queen"},
            {"💎", "gem diamond"}, {"💰", "money bag rich"}, {"🎮", "game controller gaming"},
            {"🎧", "headphones music"}, {"🎵", "music note song"}, {"🎶", "music notes song"},
            {"🎤", "microphone sing karaoke"}, {"📸", "camera photo"}, {"🎬", "movie film clapper"},
            {"🌈", "rainbow"}, {"☀️", "sun sunny weather"}, {"🌙", "moon night"}, {"☁️", "cloud weather"},
            {"🌧️", "rain weather"}, {"❄️", "snow cold winter"}, {"🌊", "wave ocean sea water"},
            {"🌸", "flower blossom pink"}, {"🌹", "rose flower love"}, {"🌻", "sunflower flower"},
            {"🌴", "palm tree beach"}, {"🍀", "clover luck"}, {"🍁", "leaf autumn fall"},
            {"🐶", "dog puppy"}, {"🐱", "cat kitten"}, {"🦊", "fox"}, {"🐼", "panda"}, {"🦁", "lion"},
            {"🐯", "tiger"}, {"🐸", "frog"}, {"🦄", "unicorn magic"}, {"🐉", "dragon"}, {"🦋", "butterfly"},
            {"🐝", "bee"}, {"🐢", "turtle"}, {"🦈", "shark"}, {"🐙", "octopus"},
            {"🍕", "pizza food"}, {"🍔", "burger food"}, {"🍟", "fries food"}, {"🌮", "taco food"},
            {"🍩", "donut food sweet"}, {"🍦", "ice cream sweet"}, {"🍓", "strawberry fruit"},
            {"🍉", "watermelon fruit"}, {"☕", "coffee drink"}, {"🧋", "boba bubble tea drink"},
            {"⚽", "soccer football ball sport"}, {"🏀", "basketball ball sport"}, {"🏈", "american football sport"},
            {"🚀", "rocket space launch"}, {"✈️", "plane travel flight"}, {"🚗", "car drive"},
            {"🏠", "house home"}, {"🌍", "earth world globe"}, {"⏰", "alarm clock time"},
            {"💡", "idea light bulb"}, {"📍", "location pin place"}, {"✅", "check yes done"},
            {"❌", "cross no wrong"}, {"❓", "question"}, {"❗", "exclamation"}, {"💬", "speech chat bubble"},
            {"💭", "thought bubble think"}, {"🆒", "cool"}, {"🆕", "new"}, {"🔝", "top"},
            {"🎃", "pumpkin halloween"}, {"🎄", "christmas tree xmas"}, {"🎅", "santa christmas xmas"},
            {"🗡️", "sword dagger zelda"}, {"🛡️", "shield zelda"}, {"🏴‍☠️", "pirate flag one piece"},
            {"🍖", "meat food one piece"}, {"⚓", "anchor sea pirate"},
    };

    private StickerCatalog() {
    }

    /** Every sticker whose words contain {@code query} (all of them when it's blank). */
    static List<String> search(String query) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String[] s : STICKERS) {
            if (q.isEmpty() || s[1].contains(q)) out.add(s[0]);
        }
        return out;
    }
}
