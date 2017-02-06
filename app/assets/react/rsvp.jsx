function Button(props) {
    const classes = "button is-large "+props.colour;
    return (
        <button className={classes} value={props.value} onClick={() => props.onClick()}>{props.text}</button>
    )
}

class Question extends React.Component {
    render() {
        const question = this.props.question.question;
        const answers = this.props.question.answers;
        const answerButtons = answers.map((answer, index) => {
            return (
                <Button key={index} colour={answer.colour} text={answer.text} onClick={() => this.props.onAnswer(answer)}/>
            );
        });
        return (
            <div>
                <h2 className="heading">{question}</h2>
                {answerButtons}
            </div>
        )
    }
}

class Rsvp extends React.Component {
    constructor() {
        super();
        this.state = {
            questions: null,
            page: "yesOrNo"
        }
    }
    componentDidMount() {
        // get questions
        fetch("/rsvp/questions", {
            credentials: 'include'
        }).then((response) => {
            return response.json().then((json) => {
                this.setState({questions: json.questions, page: json.startPage});
            });
        });
        // get any responses
    }
    onAnswer(question, answer) {
        console.log(question);
        console.log(answer);

        fetch("/rsvp/update", {
            credentials: 'include',
            headers: {
                'Content-Type': 'application/json',
                'Csrf-Token': window.csrf
            },
            method: 'POST',
            body: JSON.stringify({field: question.updateKey, value: answer.updateKey})
        }).then((res) => console.log(res));

        const nextPage = answer.nextQuestion;
        if (nextPage) {
            this.setState({page: nextPage})
        } else {
            // deal with case of being finished
        }
    }
    render() {
        if (this.state.questions) {
            const question = this.state.questions[this.state.page];
            return (
                <Question question={question} onAnswer={(answer) => this.onAnswer(question, answer)}/>
            );
        } else {
            return (
                <p>Loading RSVP information...</p>
            );
        }
    }
}

ReactDOM.render(
    <Rsvp />,
    document.getElementById('container')
);